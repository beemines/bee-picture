package com.bee.beepicturebackend.api.imagesearch.sub;

import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class GetImagePageUrlApi {

    /**
     * 获取图片页面地址
     *
     * @param imageUrl
     * @return
     */
    public static String getImagePageUrl(String imageUrl) {
        Map<String, Object> formData = new HashMap<>();
        formData.put("image", imageUrl);
        formData.put("tn", "pc");
        formData.put("from", "pc");
        formData.put("image_source", "PC_UPLOAD_URL");

        long uptime = System.currentTimeMillis();
        String url = "https://graph.baidu.com/upload?uptime=" + uptime;

        try {
            try (HttpResponse response = HttpRequest.post(url)
                    .form(formData)
                    .timeout(5000)
                    .execute()) {
                if (HttpStatus.HTTP_OK != response.getStatus()) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "以图搜图服务暂不可用（HTTP " + response.getStatus() + "），请稍后重试");
                }
                return parseResponse(response.body());
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception e) {
            log.warn("以图搜图上传请求失败，异常类型={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务连接或响应异常，请稍后重试");
        }
    }

    private static String parseResponse(String responseBody) {
        JSONObject result = JSONUtil.parseObj(responseBody);
        Integer status = result.getInt("status");
        if (status == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务返回的结果格式异常，请稍后重试");
        }
        if (status != 0) {
            log.warn("以图搜图上传被拒绝，status={}，响应字段={}", status, result.keySet());
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "以图搜图服务拒绝请求（状态码 " + status + "），请稍后重试");
        }
        Object data = result.get("data");
        if (!(data instanceof JSONObject)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务未返回可用结果，请更换图片或稍后重试");
        }
        String rawUrl = ((JSONObject) data).getStr("url");
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务未返回可用结果，请更换图片或稍后重试");
        }
        // Only decode a URL that is encoded as a whole; query escapes and '+' belong to an existing URL.
        String searchResultUrl = rawUrl.startsWith("http://") || rawUrl.startsWith("https://")
                ? rawUrl : URLUtil.decode(rawUrl, StandardCharsets.UTF_8);
        URI location = URI.create(searchResultUrl);
        if (!("https".equalsIgnoreCase(location.getScheme()) || "http".equalsIgnoreCase(location.getScheme()))
                || location.getHost() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务未返回有效链接，请稍后重试");
        }
        return searchResultUrl;
    }

    public static void main(String[] args) {
        // 测试以图搜图功能
        String imageUrl = "https://raw.githubusercontent.com/github/explore/main/topics/java/java.png";
        String result = getImagePageUrl(imageUrl);
        System.out.println("搜索成功，结果 URL：" + result);
    }
}
