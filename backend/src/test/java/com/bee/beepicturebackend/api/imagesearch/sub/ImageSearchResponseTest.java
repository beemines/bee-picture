package com.bee.beepicturebackend.api.imagesearch.sub;

import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ImageSearchResponseTest {

    @Test
    void resultPageDecodesAllJsonStringEscapesWithoutChangingEncodedQueryValues() throws Exception {
        String html = "<script>window.result={\"firstUrl\":\"https:\\/\\/graph.baidu.com\\/ajax?token=a%2Fb\\u0026name=x+y\"}</script>";
        withResponse(200, "text/html", html, url -> assertEquals(
                "https://graph.baidu.com/ajax?token=a%2Fb&name=x+y", GetImageFirstUrlApi.getImageFirstUrl(url)));
    }

    @Test
    void aVerificationPageReportsTheExternalRestrictionWithoutTryingToSolveIt() throws Exception {
        withResponse(200, "text/html", "<html><title>百度安全验证</title></html>", url -> {
            BusinessException error = assertThrows(BusinessException.class, () -> GetImageFirstUrlApi.getImageFirstUrl(url));
            assertEquals(ErrorCode.OPERATION_ERROR.getCode(), error.getCode());
            assertTrue(error.getMessage().contains("验证"));
            assertTrue(error.getMessage().contains("稍后"));
        });
    }

    @Test
    void aPageWithoutResultsGivesAnActionableFailureInsteadOfAnOpaqueSearchFailure() throws Exception {
        withResponse(200, "text/html", "<html><title>百度识图</title><script>window.result={}</script></html>", url -> {
            BusinessException error = assertThrows(BusinessException.class, () -> GetImageFirstUrlApi.getImageFirstUrl(url));
            assertTrue(error.getMessage().contains("未返回"));
            assertTrue(error.getMessage().contains("更换图片"));
        });
    }

    @Test
    void resultPageHttpFailuresKeepTheSanitizedStatus() throws Exception {
        withResponse(503, "text/html", "unavailable", url -> {
            BusinessException error = assertThrows(BusinessException.class, () -> GetImageFirstUrlApi.getImageFirstUrl(url));
            assertTrue(error.getMessage().contains("HTTP 503"));
        });
    }

    @Test
    void imageListHttpFailuresKeepTheSanitizedStatus() throws Exception {
        withResponse(503, "application/json", "{}", url -> {
            BusinessException error = assertThrows(BusinessException.class, () -> GetImageListApi.getImageList(url));
            assertTrue(error.getMessage().contains("HTTP 503"));
        });
    }

    @Test
    void imageListDoesNotHideAnExternalRejectionBehindAnEmptyResult() throws Exception {
        withResponse(200, "application/json", "{\"status\":1,\"msg\":\"Reject\",\"data\":null}", url -> {
            BusinessException error = assertThrows(BusinessException.class, () -> GetImageListApi.getImageList(url));
            assertTrue(error.getMessage().contains("状态码 1"));
        });
    }

    @Test
    void imageListPreservesRealResultsAndAllowsAGenuineEmptyList() throws Exception {
        withResponse(200, "application/json", "{\"data\":{\"list\":[{\"thumbUrl\":\"https://example.com/thumb.png\",\"fromUrl\":\"https://example.com/source\"}]}}", url -> {
            assertEquals("https://example.com/thumb.png", GetImageListApi.getImageList(url).get(0).getThumbUrl());
        });
        withResponse(200, "application/json", "{\"data\":{\"list\":[]}}", url -> assertTrue(GetImageListApi.getImageList(url).isEmpty()));
    }

    @Test
    void uploadRejectionReportsTheProviderStatusWithoutEchoingItsBodyOrImageUrl() {
        String response = "{\"status\":1,\"msg\":\"Reject https://private.example/image?secret=private\",\"data\":null}";
        BusinessException error = assertThrows(BusinessException.class, () -> parseUpload(response));
        assertTrue(error.getMessage().contains("状态码 1"));
        assertFalse(error.getMessage().contains("private"));
    }

    @Test
    void uploadKeepsAnAlreadyDecodedUrlAndItsPercentEncodedQueryValuesIntact() {
        String response = "{\"status\":0,\"data\":{\"url\":\"https://graph.baidu.com/s?token=a%2Fb&name=x+y\"}}";
        assertEquals("https://graph.baidu.com/s?token=a%2Fb&name=x+y", parseUpload(response));
    }

    @Test
    void uploadDecodesAnEncodedRedirectExactlyOnce() {
        String response = "{\"status\":0,\"data\":{\"url\":\"https%3A%2F%2Fgraph.baidu.com%2Fs%3Ftoken%3Da%252Fb%26name%3Dx%2By\"}}";
        assertEquals("https://graph.baidu.com/s?token=a%2Fb&name=x+y", parseUpload(response));
    }

    @Test
    void uploadMissingDataIsAnActionableBusinessFailure() {
        BusinessException error = assertThrows(BusinessException.class, () -> parseUpload("{\"status\":0,\"data\":null}"));
        assertTrue(error.getMessage().contains("未返回"));
        assertTrue(error.getMessage().contains("稍后"));
    }

    private String parseUpload(String response) {
        return ReflectionTestUtils.invokeMethod(GetImagePageUrlApi.class, "parseResponse", response);
    }

    private void withResponse(int status, String contentType, String body, UrlAssertion assertion) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            assertion.accept("http://127.0.0.1:" + server.getAddress().getPort() + "/fixture");
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface UrlAssertion {
        void accept(String url) throws Exception;
    }
}
