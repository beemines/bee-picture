package com.bee.beepicturebackend.api.imagesearch.sub;

import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class GetImageFirstUrlApi {

    private static final Pattern FIRST_URL = Pattern.compile("\"firstUrl\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")");
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * 获取图片列表页面地址
     *
     * @param url
     * @return
     */
    public static String getImageFirstUrl(String url) {
        try {
            // 使用 Jsoup 获取 HTML 内容
            Document document = Jsoup.connect(url)
                    .timeout(5000)
                    .get();
            if (document.title().contains("安全验证")) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务要求安全验证，暂时无法完成搜索，请稍后重试");
            }

            // 获取所有 <script> 标签
            Elements scriptElements = document.getElementsByTag("script");

            // 遍历找到包含 `firstUrl` 的脚本内容
            for (Element script : scriptElements) {
                String scriptContent = script.html();
                if (scriptContent.contains("\"firstUrl\"")) {
                    // 正则表达式提取 firstUrl 的值
                    Matcher matcher = FIRST_URL.matcher(scriptContent);
                    if (matcher.find()) {
                        String firstUrl = JSON_MAPPER.readValue(matcher.group(1), String.class);
                        if (!firstUrl.trim().isEmpty()) {
                            return firstUrl;
                        }
                    }
                }
            }

            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务未返回图片结果，请更换图片或稍后重试");
        } catch (BusinessException exception) {
            throw exception;
        } catch (HttpStatusException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "以图搜图服务暂不可用（HTTP " + exception.getStatusCode() + "），请稍后重试");
        } catch (Exception e) {
            log.warn("以图搜图结果页请求失败，异常类型={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务连接或响应异常，请稍后重试");
        }
    }

    public static void main(String[] args) {
        // 请求目标 URL
        if (args.length == 0) {
            System.out.println("Pass a current search URL as the first argument; captured session links are not stored in source.");
            return;
        }
        String url = args[0];
        String imageFirstUrl = getImageFirstUrl(url);
        System.out.println("搜索成功，结果 URL：" + imageFirstUrl);
    }
}
