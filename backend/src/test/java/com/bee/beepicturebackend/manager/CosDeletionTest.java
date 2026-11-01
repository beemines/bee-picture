package com.bee.beepicturebackend.manager;

import com.bee.beepicturebackend.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CosDeletionTest {

    private CosManager manager;
    private CosClientConfig configuration;
    private final List<String> deleted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        deleted.clear();
        configuration = new CosClientConfig();
        configuration.setHost("https://owned.example.invalid");
        configuration.setBucket("qa-bucket");
        COSClient client = mock(COSClient.class);
        doAnswer(invocation -> {
            assertEquals("qa-bucket", invocation.getArgument(0));
            deleted.add(invocation.getArgument(1));
            return null;
        }).when(client).deleteObject(anyString(), any());
        manager = new CosManager();
        ReflectionTestUtils.setField(manager, "cosClientConfig", configuration);
        ReflectionTestUtils.setField(manager, "cosClient", client);
    }

    @Test
    void configuredHostUrlIsConvertedToAnObjectKey() {
        manager.deleteObject("https://owned.example.invalid/public/120/photo.webp?imageMogr2/format/png#preview");
        assertEquals(Collections.singletonList("public/120/photo.webp"), deleted);
    }

    @Test
    void urlEncodingIsDecodedOnceWithoutTurningPlusIntoASpace() {
        manager.deleteObject("https://owned.example.invalid/public/a%20b+%2B%252F.webp");
        assertEquals(Collections.singletonList("public/a b++%2F.webp"), deleted);
    }

    @Test
    void rawKeysKeepTheirLiteralEncodedCharacters() {
        manager.deleteObject("public/a%2Fb+photo.webp");
        assertEquals(Collections.singletonList("public/a%2Fb+photo.webp"), deleted);
    }

    @Test
    void configuredHostPathPrefixIsRemovedWithoutDroppingTheObjectsLeadingSlash() {
        configuration.setHost("https://owned.example.invalid/assets/");
        manager.deleteObject("https://owned.example.invalid/assets//public/photo.webp");
        assertEquals(Collections.singletonList("/public/photo.webp"), deleted);
    }

    @Test
    void configuredHostPathCannotMatchADifferentPrefix() {
        configuration.setHost("https://owned.example.invalid/assets");
        manager.deleteObject("https://owned.example.invalid/assets-other/photo.webp");
        assertTrue(deleted.isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "https://foreign.example.invalid/photo.webp",
            "https://owned.example.invalid.foreign.example/photo.webp", "https://owned.example.invalid:444/photo.webp",
            "https://user@owned.example.invalid/photo.webp", "//foreign.example.invalid/photo.webp",
            "file:///public/photo.webp", "https://owned.example.invalid/bad%ZZ.webp", "https://owned.example.invalid/"})
    void foreignOrMalformedLocationsNeverReachCos(String location) {
        manager.deleteObject(location);
        assertTrue(deleted.isEmpty());
    }
}
