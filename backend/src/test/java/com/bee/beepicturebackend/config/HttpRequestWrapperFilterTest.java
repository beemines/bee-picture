package com.bee.beepicturebackend.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HttpRequestWrapperFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "application/json; charset=utf-8"})
    void jsonBodyRemainsAvailableAfterAuthorizationReadsIt(String contentType) throws Exception {
        String body = "{\"id\":120,\"name\":\"QA裁剪后图片😀\"}";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));

        new HttpRequestWrapperFilter().doFilter(request, new MockHttpServletResponse(), (filteredRequest, response) -> {
            assertEquals(body, filteredRequest.getReader().lines().collect(Collectors.joining()));
            assertEquals(body, filteredRequest.getReader().lines().collect(Collectors.joining()));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=picture-upload"})
    void nonJsonRequestsReachTheChainWithoutWrappingOrConsumingTheirBody(String contentType) throws Exception {
        String body = "picture upload body";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));

        new HttpRequestWrapperFilter().doFilter(request, new MockHttpServletResponse(), (filteredRequest, response) -> {
            assertSame(request, filteredRequest);
            assertEquals(body, new String(filteredRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });
    }
}
