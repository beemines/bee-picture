package com.bee.beepicturebackend.api.imagesearch.sub;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.bee.beepicturebackend.api.imagesearch.model.ImageSearchResult;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class GetImageListApi {

    /**
     * 获取图片列表
     *
     * @param url
     * @return
     */
    public static List<ImageSearchResult> getImageList(String url) {
        try {
            // 发起GET请求
            try (HttpResponse response = HttpUtil.createGet(url).timeout(5000).execute()) {
                int statusCode = response.getStatus();
                if (statusCode != 200) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "以图搜图服务暂不可用（HTTP " + statusCode + "），请稍后重试");
                }
                return processResponse(response.body());
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception e) {
            log.warn("以图搜图图片列表请求失败，异常类型={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务连接或响应异常，请稍后重试");
        }
    }

    /**
     * 处理接口响应内容
     *
     * @param responseBody 接口返回的JSON字符串
     */
    private static List<ImageSearchResult> processResponse(String responseBody) {
        // 解析响应对象
        JSONObject jsonObject = new JSONObject(responseBody);
        Integer status = jsonObject.getInt("status");
        if (status != null && status != 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "以图搜图服务拒绝请求（状态码 " + status + "），请稍后重试");
        }
        Object data = jsonObject.get("data");
        if (!(data instanceof JSONObject) || !(((JSONObject) data).get("list") instanceof JSONArray)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图服务未返回图片列表，请更换图片或稍后重试");
        }
        JSONArray list = ((JSONObject) data).getJSONArray("list");
        return JSONUtil.toList(list, ImageSearchResult.class);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Pass a current search URL as the first argument; captured session links are not stored in source.");
            return;
        }
        String url = args[0];
        List<ImageSearchResult> imageList = getImageList(url);
        System.out.println("搜索成功" + imageList);
    }
}
