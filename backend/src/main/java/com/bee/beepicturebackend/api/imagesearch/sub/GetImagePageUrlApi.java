package com.bee.beepicturebackend.api.imagesearch.sub;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONUtil;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
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
            HttpResponse response = HttpRequest.post(url)
                    // 这里需要指定acs-token 不然会响应系统异常
                    .header("acs-token", RandomUtil.randomString(1))
                    .form(formData)
                    .timeout(5000)
                    .execute();
            // 先打印 HTTP 状态码
            System.out.println("HTTP状态码：" + response.getStatus());

            // 再打印响应体
            String responseBody = response.body();
            System.out.println("响应内容：" + responseBody);

            if (HttpStatus.HTTP_OK != response.getStatus()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "接口调用失败，HTTP状态码：" + response.getStatus());
            }

            Map<String, Object> result = JSONUtil.toBean(responseBody, Map.class);
            System.out.println("解析后的结果：" + result);

            if (result == null || !Integer.valueOf(0).equals(result.get("status"))) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "接口调用失败，返回结果：" + responseBody);
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            String rawUrl = (String) data.get("url");

            String searchResultUrl = URLUtil.decode(rawUrl, StandardCharsets.UTF_8);

            if (searchResultUrl == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "未返回有效结果");
            }

            return searchResultUrl;
        } catch (Exception e) {
            log.error("搜索失败，imageUrl={}", imageUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "搜索失败：" + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // 测试以图搜图功能
        String imageUrl = "https://raw.githubusercontent.com/github/explore/main/topics/java/java.png";
        String result = getImagePageUrl(imageUrl);
        System.out.println("搜索成功，结果 URL：" + result);
    }
}
