package com.bee.beepicturebackend.manager;

import cn.hutool.core.io.FileUtil;
import com.bee.beepicturebackend.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Component
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    COSClient cosClient;

    /**
     *  上传对象
     * @param key 唯一键
     * @param file 文件
     * @return
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 下载对象
     *
     * @param key 唯一键
     */
    public COSObject getObject(String key) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(cosClientConfig.getBucket(), key);
        return cosClient.getObject(getObjectRequest);
    }

    /**
     * 上传对象（附带图片信息）
     *
     * @param key  唯一键
     * @param file 文件
     */
    public PutObjectResult putPictureObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        // 对图片进行处理（获取基本信息也被视作为一种处理）
        PicOperations picOperations = new PicOperations();
        // 1 表示返回原图信息
        picOperations.setIsPicInfo(1);
        List<PicOperations.Rule> rules = new ArrayList<>();
        // 1.图片压缩（转成 webp 格式）
        String webpKey = FileUtil.mainName(key) + ".webp";
        PicOperations.Rule compressRule = new PicOperations.Rule();
        compressRule.setRule("imageMogr2/format/webp");
        compressRule.setBucket(cosClientConfig.getBucket());
        compressRule.setFileId(webpKey);
        rules.add(compressRule);
        // 2.缩略图处理，仅对 > 100KB 的图片生成缩略图
        if(file.length()>100*1024){
            PicOperations.Rule thumbnailRule = new PicOperations.Rule();
            thumbnailRule.setBucket(cosClientConfig.getBucket());
            String thumbnailKey = FileUtil.mainName(key) + "_thumbnail." + FileUtil.getSuffix(key);
            thumbnailRule.setFileId(thumbnailKey);
            // 缩放规则 /thumbnail/<Width>x<Height>>（如果大于原图宽高，则不处理）
            thumbnailRule.setRule(String.format("imageMogr2/thumbnail/%sx%s>", 300, 300));
            rules.add(thumbnailRule);
        }
        // 构造处理参数
        picOperations.setRules(rules);
        putObjectRequest.setPicOperations(picOperations);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 删除对象
     *
     * @param key 文件 key，或配置域名下的完整 URL
     */
    public void deleteObject(String key) throws CosClientException {
        if (key == null || key.isBlank()) {
            return;
        }
        if (key.contains("://") || key.startsWith("//")) {
            key = getObjectKeyFromUrl(key);
        }
        if (key == null || key.isBlank() || "/".equals(key)) {
            return;
        }
        cosClient.deleteObject(cosClientConfig.getBucket(), key);
    }

    /** 将配置域名下的公开 URL 转为 SDK 对象键；其他来源不属于当前存储命名空间。 */
    public String getObjectKeyFromUrl(String url) {
        if (url == null || url.isBlank() || cosClientConfig.getHost() == null || cosClientConfig.getHost().isBlank()) {
            return null;
        }
        try {
            URI configured = URI.create(cosClientConfig.getHost());
            URI object = URI.create(url);
            if (configured.getHost() == null || object.getHost() == null
                    || object.getUserInfo() != null || configured.getUserInfo() != null
                    || !("https".equalsIgnoreCase(object.getScheme()) || "http".equalsIgnoreCase(object.getScheme()))
                    || !object.getScheme().equalsIgnoreCase(configured.getScheme())
                    || !object.getHost().equalsIgnoreCase(configured.getHost())
                    || effectivePort(object) != effectivePort(configured)) {
                return null;
            }
            String prefix = configured.getPath();
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            // URI.getPath 解码一次百分号转义，保留字面量 +，也不会二次解码 %25。
            String path = object.getPath();
            if (path == null || !path.startsWith(prefix + "/")) {
                return null;
            }
            String key = path.substring(prefix.length() + 1);
            return key.isBlank() || "/".equals(key) ? null : key;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

}
