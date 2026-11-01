package com.bee.beepicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.bee.beepicturebackend.model.dto.space.SpaceAddRequest;
import com.bee.beepicturebackend.model.dto.space.SpaceQueryRequest;
import com.bee.beepicturebackend.model.dto.space.analyze.*;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.model.vo.SpaceVO;
import com.bee.beepicturebackend.model.vo.space.analyze.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @author beemines
 * @createDate 2026-03-20 19:49:50
 */
public interface SpaceAnalyzeService extends IService<Space> {

    /**
     * 获取空间使用情况
     * @param spaceUsageAnalyzeRequest 请求参数
     * @param loginUser 当前登录用户
     * @return 空间分析情况
     */
    SpaceUsageAnalyzeResponse getSpaceUsageAnalyze(SpaceUsageAnalyzeRequest spaceUsageAnalyzeRequest, User loginUser);

    List<SpaceCategoryAnalyzeResponse> getSpaceCategoryAnalyze(SpaceCategoryAnalyzeRequest spaceCategoryAnalyzeRequest,
                                                               User loginUser);

    List<SpaceTagAnalyzeResponse> getSpaceTagAnalyze(SpaceTagAnalyzeRequest spaceTagAnalyzeRequest, User loginUser);

    List<SpaceSizeAnalyzeResponse> getSpaceSizeAnalyze(SpaceSizeAnalyzeRequest spaceSizeAnalyzeRequest, User loginUser);

    List<SpaceUserAnalyzeResponse> getSpaceUserAnalyze(SpaceUserAnalyzeRequest spaceUserAnalyzeRequest, User loginUser);

    List<Space> getSpaceRankAnalyze(SpaceRankAnalyzeRequest spaceRankAnalyzeRequest, User loginUser);
}
