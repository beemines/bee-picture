package com.bee.beepicturebackend.controller;

import com.bee.beepicturebackend.annotation.AuthCheck;
import com.bee.beepicturebackend.common.BaseResponse;
import com.bee.beepicturebackend.common.ResultUtils;
import com.bee.beepicturebackend.constant.UserConstant;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.manager.CosManager;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/file")
@Slf4j
public class FileController {

    @Resource
    private final CosManager cosManager;

    public FileController(CosManager cosManager) {
        this.cosManager = cosManager;
    }

    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/test/upload")
    public BaseResponse<String> testUploadFile(@RequestPart("file")MultipartFile multipartFile){
        //文件目录
        String filename = multipartFile.getOriginalFilename();
        String filePath = String.format("/test/%s",filename);
        File file = null;
        try {
            //上传文件
            file = File.createTempFile(filename, null);
            multipartFile.transferTo(file);
            cosManager.putObject(filePath,file);
            //返回可访问地址
            return ResultUtils.success(filePath);
        } catch (Exception e) {
            log.error("file upload error，filepath = " + filePath,e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"上传失败");
        }finally {
            //删除临时文件
            boolean delete = file.delete();
            if(!delete){
                log.error("file delete error,filePath = {}",filePath);
            }
        }
    }

    /**
     * 测试文件下载
     *
     * @param filepath 文件路径
     * @param response 响应对象
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GetMapping("/test/download/")
    public void testDownloadFile(String filepath, HttpServletResponse response) throws IOException {
        COSObjectInputStream cosObjectInput = null;
        try {
            COSObject cosObject = cosManager.getObject(filepath);
            cosObjectInput = cosObject.getObjectContent();
            // 处理下载到的流
            byte[] bytes = IOUtils.toByteArray(cosObjectInput);
            // 设置响应头
            response.setContentType("application/octet-stream;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + filepath);
            // 写入响应
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("file download error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载失败");
        } finally {
            if (cosObjectInput != null) {
                cosObjectInput.close();
            }
        }
    }


}
