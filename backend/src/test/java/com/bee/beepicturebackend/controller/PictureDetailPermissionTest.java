package com.bee.beepicturebackend.controller;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.spring.SaTokenContextForSpring;
import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.api.imagesearch.model.ImageSearchResult;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.manager.auth.SpaceUserAuthManager;
import com.bee.beepicturebackend.manager.auth.StpInterfaceImpl;
import com.bee.beepicturebackend.manager.auth.StpKit;
import com.bee.beepicturebackend.mapper.PictureMapper;
import com.bee.beepicturebackend.mapper.SpaceUserMapper;
import com.bee.beepicturebackend.mapper.UserMapper;
import com.bee.beepicturebackend.model.entity.Picture;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.SpaceUser;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.model.dto.picture.PictureQueryRequest;
import com.bee.beepicturebackend.model.dto.picture.SearchPictureByPictureRequest;
import com.bee.beepicturebackend.model.vo.PictureVO;
import com.bee.beepicturebackend.service.SpaceService;
import com.bee.beepicturebackend.service.impl.PictureServiceImpl;
import com.bee.beepicturebackend.service.impl.SpaceUserServiceImpl;
import com.bee.beepicturebackend.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.bee.beepicturebackend.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/** Exercises the detail response with real permission rules; only database mappers are replaced. */
class PictureDetailPermissionTest {

    private static final long PICTURE_ID = 9007199254741120L;
    private static final long LOGIN_USER_ID = 11L;
    private static final long OTHER_USER_ID = 22L;
    private static final long SPACE_ID = 100L;
    private static final List<String> ADMIN_PERMISSIONS = Arrays.asList(
            "spaceUser:manage", "picture:view", "picture:upload", "picture:edit", "picture:delete");

    private PictureController controller;
    private MockHttpServletRequest request;
    private User loginUser;
    private Picture picture;
    private SpaceService spaceService;
    private SpaceUser membership;
    private PictureServiceImpl pictureService;
    private SaTokenContext previousContext;
    private StpInterface previousStpInterface;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Picture.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SpaceUser.class);

        loginUser = new User();
        loginUser.setId(LOGIN_USER_ID);
        loginUser.setUserRole("user");
        picture = new Picture();
        picture.setId(PICTURE_ID);
        picture.setUserId(LOGIN_USER_ID);
        picture.setReviewStatus(1);
        picture.setTags("[]");

        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.selectById(LOGIN_USER_ID)).thenReturn(loginUser);
        UserServiceImpl userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "entityClass", User.class);

        PictureMapper pictureMapper = mock(PictureMapper.class);
        when(pictureMapper.selectById(PICTURE_ID)).thenReturn(picture);
        when(pictureMapper.selectOne(any())).thenReturn(picture);
        pictureService = spy(new PictureServiceImpl());
        ReflectionTestUtils.setField(pictureService, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(pictureService, "entityClass", Picture.class);
        ReflectionTestUtils.setField(pictureService, "userService", userService);

        SpaceUserMapper spaceUserMapper = mock(SpaceUserMapper.class);
        when(spaceUserMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            Map<String, Object> parameters = query.getParamNameValuePairs();
            if (membership != null && parameters.containsValue(membership.getSpaceId())
                    && parameters.containsValue(membership.getUserId())) {
                return membership;
            }
            return null;
        });
        SpaceUserServiceImpl spaceUserService = new SpaceUserServiceImpl();
        ReflectionTestUtils.setField(spaceUserService, "baseMapper", spaceUserMapper);
        ReflectionTestUtils.setField(spaceUserService, "entityClass", SpaceUser.class);

        spaceService = mock(SpaceService.class);
        SpaceUserAuthManager authManager = new SpaceUserAuthManager();
        ReflectionTestUtils.setField(authManager, "spaceUserService", spaceUserService);
        ReflectionTestUtils.setField(authManager, "userService", userService);

        StpInterfaceImpl stpInterface = new StpInterfaceImpl();
        ReflectionTestUtils.setField(stpInterface, "contextPath", "/api");
        ReflectionTestUtils.setField(stpInterface, "pictureService", pictureService);
        ReflectionTestUtils.setField(stpInterface, "spaceService", spaceService);
        ReflectionTestUtils.setField(stpInterface, "spaceUserService", spaceUserService);
        ReflectionTestUtils.setField(stpInterface, "userService", userService);
        ReflectionTestUtils.setField(stpInterface, "spaceUserAuthManager", authManager);

        controller = new CapturingPictureController();
        ReflectionTestUtils.setField(controller, "pictureService", pictureService);
        ReflectionTestUtils.setField(controller, "spaceService", spaceService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "spaceUserAuthManager", authManager);

        request = new MockHttpServletRequest("GET", "/api/picture/get/vo");
        request.setParameter("id", Long.toString(PICTURE_ID));
        request.getSession().setAttribute(USER_LOGIN_STATE, loginUser);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        previousContext = SaManager.getSaTokenContext();
        previousStpInterface = SaManager.getStpInterface();
        SaManager.setSaTokenContext(new SaTokenContextForSpring());
        SaManager.setStpInterface(stpInterface);
        StpKit.SPACE.login(LOGIN_USER_ID);
        StpKit.SPACE.getSession().set(USER_LOGIN_STATE, loginUser);
    }

    @AfterEach
    void tearDown() {
        StpKit.SPACE.logout();
        SaManager.setSaTokenContext(previousContext);
        SaManager.setStpInterface(previousStpInterface);
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void publicOwnerCanManageTheirPictureForEveryReviewStatus(int reviewStatus) {
        picture.setReviewStatus(reviewStatus);
        assertEquals(ADMIN_PERMISSIONS, detail().getPermissionList());
    }

    @Test
    void publicNonOwnerCanOnlyView() {
        picture.setUserId(OTHER_USER_ID);
        assertEquals(Collections.singletonList("picture:view"), detail().getPermissionList());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void publicNonOwnerCannotViewUnapprovedPictures(int reviewStatus) {
        picture.setUserId(OTHER_USER_ID);
        picture.setReviewStatus(reviewStatus);
        assertError(ErrorCode.NO_AUTH_ERROR);
    }

    @Test
    void cachedListCannotReturnAnotherUsersPrivatePictures() {
        inSpace(0, OTHER_USER_ID);
        prepareCachedList();
        PictureQueryRequest query = new PictureQueryRequest();
        query.setSpaceId(SPACE_ID);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.listPictureVOByPageWithCache(query, request));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    @Test
    void cachedPublicListForcesApprovedPublicScope() {
        prepareCachedList();
        PictureQueryRequest query = new PictureQueryRequest();
        query.setReviewStatus(0);
        controller.listPictureVOByPageWithCache(query, request);
        assertTrue(query.isNullSpaceId());
        assertEquals(1, query.getReviewStatus());
    }

    @Test
    void cachedTeamListPreservesTheNormalSpaceReviewRules() {
        inTeam("viewer");
        prepareCachedList();
        PictureQueryRequest query = new PictureQueryRequest();
        query.setSpaceId(SPACE_ID);
        controller.listPictureVOByPageWithCache(query, request);
        assertNull(query.getReviewStatus());
    }

    @Test
    void imageSearchCannotSendAnotherUsersPrivatePictureExternally() {
        inSpace(0, OTHER_USER_ID);
        assertImageSearchDenied();
    }

    @Test
    void imageSearchCannotSendATeamPictureForANonMember() {
        inSpace(1, OTHER_USER_ID);
        assertImageSearchDenied();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void imageSearchCannotSendAnotherUsersUnapprovedPublicPicture(int reviewStatus) {
        picture.setUserId(OTHER_USER_ID);
        picture.setReviewStatus(reviewStatus);
        assertImageSearchDenied();
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "editor", "admin"})
    void imageSearchAllowsTeamMembersWithViewPermission(String role) {
        inTeam(role);
        assertImageSearchAllowed();
    }

    @Test
    void imageSearchAllowsPrivateSpaceOwner() {
        inSpace(0, LOGIN_USER_ID);
        picture.setUserId(OTHER_USER_ID);
        assertImageSearchAllowed();
    }

    @Test
    void imageSearchAllowsAnApprovedPublicPictureForANonOwner() {
        picture.setUserId(OTHER_USER_ID);
        assertImageSearchAllowed();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void imageSearchAllowsAnOwnersUnapprovedPublicPicture(int reviewStatus) {
        picture.setReviewStatus(reviewStatus);
        assertImageSearchAllowed();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void globalAdminCanStillViewAnUnapprovedPublicPicture(int reviewStatus) {
        loginUser.setUserRole("admin");
        picture.setUserId(OTHER_USER_ID);
        picture.setReviewStatus(reviewStatus);
        assertEquals(ADMIN_PERMISSIONS, detail().getPermissionList());
    }

    @Test
    void imageSearchRequiresLoginBeforeCallingTheExternalService() {
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        SearchPictureByPictureRequest search = new SearchPictureByPictureRequest();
        search.setPictureId(PICTURE_ID);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.searchPictureByPicture(search));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), exception.getCode());
        assertNull(((CapturingPictureController) controller).searchedUrl);
    }

    private void assertImageSearchAllowed() {
        picture.setUrl("https://example.invalid/source.webp");
        SearchPictureByPictureRequest search = new SearchPictureByPictureRequest();
        search.setPictureId(PICTURE_ID);
        assertEquals("https://example.invalid/match", controller.searchPictureByPicture(search)
                .getData().get(0).getFromUrl());
        assertEquals("https://example.invalid/source.webp?imageMogr2/format/png",
                ((CapturingPictureController) controller).searchedUrl);
    }

    private void assertImageSearchDenied() {
        Picture guardedPicture = spy(picture);
        doThrow(new AssertionError("URL accessed before view permission was checked"))
                .when(guardedPicture).getUrl();
        doReturn(guardedPicture).when(pictureService).getById(PICTURE_ID);
        SearchPictureByPictureRequest search = new SearchPictureByPictureRequest();
        search.setPictureId(PICTURE_ID);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.searchPictureByPicture(search));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    private void prepareCachedList() {
        request.setRequestURI("/api/picture/list/page/vo/cache");
        if (picture.getSpaceId() != null) {
            request.setParameter("spaceId", picture.getSpaceId().toString());
        }
        doReturn(new Page<Picture>(1, 10)).when(pictureService).page(any(Page.class), any());
    }

    @Test
    void globalAdminCanManagePublicPictures() {
        loginUser.setUserRole("admin");
        picture.setUserId(OTHER_USER_ID);
        assertEquals(ADMIN_PERMISSIONS, detail().getPermissionList());
    }

    @Test
    void privateSpaceOwnerCanManagePicturesUploadedByAnotherUser() {
        inSpace(0, LOGIN_USER_ID);
        picture.setUserId(OTHER_USER_ID);
        assertEquals(ADMIN_PERMISSIONS, detail().getPermissionList());
    }

    @Test
    void privateSpaceNonOwnerIsDeniedEvenWhenTheyUploadedThePicture() {
        inSpace(0, OTHER_USER_ID);
        assertError(ErrorCode.NO_AUTH_ERROR);
    }

    @Test
    void globalAdminCanManagePrivateSpacePictures() {
        loginUser.setUserRole("admin");
        inSpace(0, OTHER_USER_ID);
        assertEquals(ADMIN_PERMISSIONS, detail().getPermissionList());
    }

    @Test
    void teamViewerCannotEditEvenTheirOwnUpload() {
        inTeam("viewer");
        assertEquals(Collections.singletonList("picture:view"), detail().getPermissionList());
    }

    @Test
    void teamEditorCanManagePicturesWithoutMemberManagement() {
        inTeam("editor");
        picture.setUserId(OTHER_USER_ID);
        assertEquals(Arrays.asList("picture:view", "picture:upload", "picture:edit", "picture:delete"),
                detail().getPermissionList());
    }

    @Test
    void teamAdminReceivesTheirActualRolePermissions() {
        inTeam("admin");
        assertEquals(ADMIN_PERMISSIONS, detail().getPermissionList());
    }

    @Test
    void teamNonMemberIsDeniedEvenWhenTheyUploadedThePicture() {
        inSpace(1, OTHER_USER_ID);
        assertError(ErrorCode.NO_AUTH_ERROR);
    }

    @Test
    void globalAdminWithoutTeamMembershipIsStillDenied() {
        loginUser.setUserRole("admin");
        inSpace(1, OTHER_USER_ID);
        assertError(ErrorCode.NO_AUTH_ERROR);
    }

    @Test
    void missingSpaceIsNotTreatedAsPublic() {
        picture.setSpaceId(SPACE_ID);
        assertError(ErrorCode.NOT_FOUND_ERROR);
    }

    @Test
    void requestSpaceIdCannotOverrideThePicturesActualPrivateSpace() {
        inSpace(0, OTHER_USER_ID);
        Space accessibleSpace = new Space();
        accessibleSpace.setId(200L);
        accessibleSpace.setSpaceType(0);
        accessibleSpace.setUserId(LOGIN_USER_ID);
        when(spaceService.getById(200L)).thenReturn(accessibleSpace);
        request.setParameter("spaceId", "200");
        assertError(ErrorCode.NO_AUTH_ERROR);
    }

    @Test
    void publicDetailStillRequiresLogin() {
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        assertError(ErrorCode.NOT_LOGIN_ERROR);
    }

    private PictureVO detail() {
        return controller.getPictureVOById(PICTURE_ID, request).getData();
    }

    private void assertError(ErrorCode errorCode) {
        BusinessException exception = assertThrows(BusinessException.class, this::detail);
        assertEquals(errorCode.getCode(), exception.getCode());
    }

    private void inSpace(int type, long ownerId) {
        Space space = new Space();
        space.setId(SPACE_ID);
        space.setSpaceType(type);
        space.setUserId(ownerId);
        picture.setSpaceId(SPACE_ID);
        when(spaceService.getById(SPACE_ID)).thenReturn(space);
    }

    private void inTeam(String role) {
        inSpace(1, OTHER_USER_ID);
        membership = new SpaceUser();
        membership.setId(300L);
        membership.setSpaceId(SPACE_ID);
        membership.setUserId(LOGIN_USER_ID);
        membership.setSpaceRole(role);
    }

    private static class CapturingPictureController extends PictureController {
        private String searchedUrl;

        @Override
        protected List<ImageSearchResult> searchImages(String url) {
            searchedUrl = url;
            ImageSearchResult result = new ImageSearchResult();
            result.setFromUrl("https://example.invalid/match");
            result.setThumbUrl("https://example.invalid/thumbnail.png");
            return Collections.singletonList(result);
        }
    }
}
