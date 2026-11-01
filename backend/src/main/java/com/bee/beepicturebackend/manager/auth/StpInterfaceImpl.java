package com.bee.beepicturebackend.manager.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.json.JSONUtil;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.manager.auth.model.SpaceUserPermissionConstant;
import com.bee.beepicturebackend.model.entity.Picture;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.SpaceUser;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.model.enums.SpaceRoleEnum;
import com.bee.beepicturebackend.service.PictureService;
import com.bee.beepicturebackend.service.SpaceService;
import com.bee.beepicturebackend.service.SpaceUserService;
import com.bee.beepicturebackend.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

import static com.bee.beepicturebackend.constant.UserConstant.USER_LOGIN_STATE;

@Component // 保证此类被 SpringBoot 扫描，完成 Sa-Token 的自定义权限验证扩展
public class StpInterfaceImpl implements StpInterface {

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Resource
    private UserService userService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserService spaceUserService;

    @Resource
    private PictureService pictureService;

    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;

    /**
     * 返回一个账号所拥有的权限码集合
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 判断 loginType，仅对类型为 "space" 进行权限校验
        if (!StpKit.SPACE_TYPE.equals(loginType)) {
            return new ArrayList<>();
        }
        // 管理员权限，表示权限校验通过
        List<String> ADMIN_PERMISSIONS = spaceUserAuthManager.getPermissionsByRole(SpaceRoleEnum.ADMIN.getValue());
        // 获取上下文对象
        SpaceUserAuthContext authContext = getAuthContextByRequest();
        // 获取 userId
        User loginUser = (User) StpKit.SPACE.getSessionByLoginId(loginId).get(USER_LOGIN_STATE);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "用户未登录");
        }
        Long userId = loginUser.getId();
        // 如果有 spaceUserId，必然是团队空间，通过数据库查询 SpaceUser 对象
        Long spaceUserId = authContext.getSpaceUserId();
        if (spaceUserId != null) {
            SpaceUser spaceUser = spaceUserService.getById(spaceUserId);
            if (spaceUser == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到空间用户信息");
            }
            // 取出当前登录用户对应的 spaceUser
            SpaceUser loginSpaceUser = spaceUserService.lambdaQuery()
                    .eq(SpaceUser::getSpaceId, spaceUser.getSpaceId())
                    .eq(SpaceUser::getUserId, userId)
                    .one();
            if (loginSpaceUser == null) {
                return new ArrayList<>();
            }
            return spaceUserAuthManager.getPermissionsByRole(loginSpaceUser.getSpaceRole());
        }
        // 图片操作必须以数据库中图片的实际空间为准，不能由调用方的 spaceId 覆盖。
        Long spaceId = authContext.getSpaceId();
        Long pictureId = authContext.getPictureId();
        if (pictureId != null) {
            Picture picture = pictureService.lambdaQuery()
                    .eq(Picture::getId, pictureId)
                    .select(Picture::getId, Picture::getSpaceId, Picture::getUserId)
                    .one();
            if (picture == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到图片信息");
            }
            spaceId = picture.getSpaceId();
            // 公共图库，仅本人或管理员可操作
            if (spaceId == null) {
                if (picture.getUserId().equals(userId) || userService.isAdmin(loginUser)) {
                    return ADMIN_PERMISSIONS;
                } else {
                    // 不是自己的图片，仅可查看
                    return Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW);
                }
            }
        }
        if (spaceId == null) {
            // 只有新建公共图片允许没有资源范围；成员管理等请求不能省略空间来绕过鉴权。
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            String path = getRequestPath(request);
            if ("picture/upload".equals(path) || "picture/upload/url".equals(path)) {
                return ADMIN_PERMISSIONS;
            }
            return new ArrayList<>();
        }
        // 获取 Space 对象
        Space space = spaceService.getById(spaceId);
        if (space == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到空间信息");
        }
        return spaceUserAuthManager.getPermissionList(space, loginUser);
    }

    /**
     * 本项目中不使用。返回一个账号所拥有的角色标识集合 (权限与角色可分开校验)
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return new ArrayList<>();
    }

    /**
     * 从请求中获取上下文对象
     */
    private SpaceUserAuthContext getAuthContextByRequest() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String contentType = request.getContentType();
        SpaceUserAuthContext parameters;
        // 获取请求参数
        if (contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
            String body = ServletUtil.getBody(request);
            parameters = JSONUtil.toBean(body, SpaceUserAuthContext.class);
        } else {
            Map<String, String> paramMap = ServletUtil.getParamMap(request);
            parameters = BeanUtil.toBean(paramMap, SpaceUserAuthContext.class);
        }
        // 仅接受当前接口实际使用的标识；嵌套实体和其他模块的标识不能提供授权。
        SpaceUserAuthContext authRequest = new SpaceUserAuthContext();
        String path = getRequestPath(request);
        Long id = parameters.getId();
        switch (path) {
            case "picture/upload":
            case "picture/upload/url":
            case "picture/list/page/vo":
                authRequest.setPictureId(id);
                authRequest.setSpaceId(parameters.getSpaceId());
                break;
            case "picture/delete":
            case "picture/edit":
            case "picture/get/vo":
                authRequest.setPictureId(id);
                break;
            case "picture/out_painting/create_task":
                authRequest.setPictureId(parameters.getPictureId());
                break;
            case "picture/search/color":
            case "picture/edit/batch":
            case "spaceUser/add":
                authRequest.setSpaceId(parameters.getSpaceId());
                break;
            case "spaceUser/get":
            case "spaceUser/list":
                authRequest.setSpaceUserId(id);
                authRequest.setSpaceId(parameters.getSpaceId());
                break;
            case "spaceUser/delete":
            case "spaceUser/edit":
                authRequest.setSpaceUserId(id);
                break;
            default:
                // 未识别的路由不能降级为使用调用方提供的空间授权。
        }
        return authRequest;
    }

    private String getRequestPath(HttpServletRequest request) {
        Object matchedPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matchedPattern instanceof PathPattern) {
            return normalizeRoute(((PathPattern) matchedPattern).getPatternString());
        }
        if (matchedPattern instanceof String) {
            return normalizeRoute((String) matchedPattern);
        }
        if (matchedPattern != null) {
            return "";
        }
        // 非 MVC 调用使用与 Spring 一致的路径解析：解码一次，并移除各段的矩阵参数。
        try {
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if ((request.getContextPath() == null || request.getContextPath().isEmpty())
                    && contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath + "/")) {
                path = path.substring(contextPath.length());
            }
            return normalizeRoute(path);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private String normalizeRoute(String path) {
        if (path == null) {
            return "";
        }
        String route = path.startsWith("/") ? path.substring(1) : path;
        return route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
    }


}
