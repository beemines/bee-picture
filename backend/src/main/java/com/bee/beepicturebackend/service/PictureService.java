package com.bee.beepicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bee.beepicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.bee.beepicturebackend.config.CosClientConfig;
import com.bee.beepicturebackend.model.dto.picture.*;
import com.bee.beepicturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.extension.service.IService;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.model.vo.PictureVO;
import com.qcloud.cos.exception.CosClientException;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author beemines
* @description 针对表【picture(图片)】的数据库操作Service
* @createDate 2026-03-07 17:49:28
*/
public interface PictureService extends IService<Picture> {

    /**
     * 上传图片
     *
     * @param inputSource
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */
    PictureVO uploadPicture(Object inputSource,
                            PictureUploadRequest pictureUploadRequest,
                            User loginUser);


    /**
     * 编辑图片
     *
     * @param pictureEditRequest
     * @param loginUser
     */
    void editPicture(PictureEditRequest pictureEditRequest, User loginUser);
    /**
     * 获取查询条件
     *
     * @param pictureQueryRequest
     * @return
     */
    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

    /**
     * 获取单个图片封装
     */
    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    /**
     * 分页获取图片封装
     */
    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

    /**
     * 图片数据校验方法，用于更新和修改图片时进行判断
     */
    void validPicture(Picture picture);

    /**
     * 图片审核
     */
    void doPictureReview(PictureReviewRequest pictureReviewRequest,User loginUser);

    /**
     * 补充审核参数
     */
    void fillReviewParams(Picture picture,User loginUser);

    /**
     * 批量抓取和创建图片
     *
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return 成功创建的图片数
     */
    Integer uploadPictureByBatch(
            PictureUploadByBatchRequest pictureUploadByBatchRequest,
            User loginUser
    );

    /**
     * 删除图片文件
     *
     * @param oldPicture
     */
    void clearPictureFile(Picture oldPicture);

    void deletePicture(long pictureId, User loginUser);

    void checkPictureAuth(User loginUser, Picture picture);

    List<PictureVO> searchPictureByColor(SearchPictureByColorRequest searchPictureByColorRequest, User loginUser);

    void editPictureByBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser);


    CreateOutPaintingTaskResponse createPictureOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser);
}
