package com.bee.beepicturebackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestWrapperTest {

    @Test
    void chineseAndEmojiJsonSurvivesAuthorizationReadBeforeControllerDeserialization() throws Exception {
        String body = "{\"id\":120,\"name\":\"QA裁剪后图片😀\"}";
        RequestWrapper wrapper = wrap(body.getBytes(StandardCharsets.UTF_8));

        assertEquals(body, wrapper.getReader().lines().collect(Collectors.joining()));
        assertEquals(body, wrapper.getBody());
        assertEquals("QA裁剪后图片😀", new ObjectMapper().readTree(wrapper.getInputStream()).get("name").asText());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), wrapper.getInputStream().readAllBytes());
    }

    @Test
    void independentStreamsPreserveEveryOriginalByteIncludingLineEndings() throws Exception {
        byte[] body = new byte[258];
        for (int value = 0; value < 256; value++) {
            body[value] = (byte) value;
        }
        body[256] = '\r';
        body[257] = '\n';
        RequestWrapper wrapper = wrap(body);

        ServletInputStream first = wrapper.getInputStream();
        assertEquals(0, first.read());
        assertArrayEquals(body, wrapper.getInputStream().readAllBytes());
        byte[] remaining = new byte[257];
        System.arraycopy(body, 1, remaining, 0, remaining.length);
        assertArrayEquals(remaining, first.readAllBytes());
        assertArrayEquals(body, wrapper.getInputStream().readAllBytes());
    }

    @Test
    void cachedStreamReportsReadyAndFinishesWhenConsumed() throws Exception {
        ServletInputStream stream = wrap(new byte[] {42}).getInputStream();

        assertTrue(stream.isReady());
        assertFalse(stream.isFinished());
        assertEquals(42, stream.read());
        assertTrue(stream.isFinished());
        assertEquals(-1, stream.read());
        assertTrue(stream.isReady());
        assertTrue(wrap(new byte[0]).getInputStream().isFinished());
    }

    private RequestWrapper wrap(byte[] body) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body);
        return new RequestWrapper(request);
    }
}
