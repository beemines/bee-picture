package com.bee.beepicturebackend.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bee.beepicturebackend.config.CosClientConfig;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.manager.CosManager;
import com.bee.beepicturebackend.mapper.PictureMapper;
import com.bee.beepicturebackend.mapper.SpaceMapper;
import com.bee.beepicturebackend.model.entity.Picture;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.User;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PictureDeletionTest {

    private static final long ID = 120L;
    private static final long SPACE_ID = 100L;
    private static final String ORIGINAL_URL = "https://owned.example.invalid/public/original.webp";
    private static final String THUMBNAIL_URL = "https://owned.example.invalid/public/thumbnail.webp";
    private final Map<Long, Picture> pictures = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> rowLocks = new ConcurrentHashMap<>();
    private final List<String> cloudAttempts = Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Changes> transaction = new ThreadLocal<>();
    private final AtomicInteger cleanupCalls = new AtomicInteger();
    private RecordingDeletionService service;
    private Space space;
    private User user;
    private Picture staleInitialPicture;
    private boolean quotaFails;
    private boolean removeFails;
    private boolean cloudFails;
    private boolean referenceLookupFails;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Picture.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Space.class);
        pictures.clear();
        cloudAttempts.clear();
        rowLocks.clear();
        cleanupCalls.set(0);
        Picture target = picture(ID, ORIGINAL_URL, THUMBNAIL_URL, 40L);
        pictures.put(ID, target);
        pictures.put(121L, picture(121L, "https://owned.example.invalid/public/other.webp", null, 60L));
        space = new Space();
        space.setId(SPACE_ID);
        space.setTotalCount(2L);
        space.setTotalSize(100L);
        user = new User();
        user.setId(11L);

        CosClientConfig configuration = new CosClientConfig();
        configuration.setHost("https://owned.example.invalid");
        configuration.setBucket("qa-bucket");
        COSClient sdk = mock(COSClient.class);
        doAnswer(invocation -> {
            assertEquals("qa-bucket", invocation.getArgument(0));
            cloudAttempts.add(invocation.getArgument(1));
            if (cloudFails) {
                throw new CosClientException("Simulated cleanup failure");
            }
            return null;
        }).when(sdk).deleteObject(anyString(), anyString());
        CosManager cos = new CosManager();
        ReflectionTestUtils.setField(cos, "cosClientConfig", configuration);
        ReflectionTestUtils.setField(cos, "cosClient", sdk);

        PictureMapper pictureMapper = mock(PictureMapper.class);
        when(pictureMapper.selectById(any())).thenAnswer(invocation -> staleInitialPicture != null
                ? copy(staleInitialPicture) : copy(pictures.get(invocation.getArgument(0))));
        when(pictureMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            Long id = query.getParamNameValuePairs().values().stream()
                    .filter(value -> value instanceof Long).map(value -> (Long) value).findFirst().orElse(null);
            if (sql.toUpperCase().contains("FOR UPDATE")) {
                ReentrantLock lock = rowLocks.computeIfAbsent(id, ignored -> new ReentrantLock());
                lock.lock();
                transaction.get().locks.add(lock);
            }
            return copy(pictures.get(id));
        });
        when(pictureMapper.selectCount(any())).thenAnswer(invocation -> {
            if (referenceLookupFails) {
                throw new IllegalStateException("Simulated reference lookup failure");
            }
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment().replace("_", "").toLowerCase();
            List<Object> values = new ArrayList<>(query.getParamNameValuePairs().values());
            return pictures.values().stream().filter(picture -> values.contains(picture.getUrl())
                    || (sql.contains("thumbnailurl") && values.contains(picture.getThumbnailUrl()))).count();
        });
        SpaceMapper spaces = mock(SpaceMapper.class);
        when(spaces.update(any(), any())).thenAnswer(invocation -> releaseQuota(invocation.getArgument(1)));
        SpaceServiceImpl spaceService = new SpaceServiceImpl();
        ReflectionTestUtils.setField(spaceService, "baseMapper", spaces);
        ReflectionTestUtils.setField(spaceService, "entityClass", Space.class);
        service = new RecordingDeletionService();
        ReflectionTestUtils.setField(service, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(service, "entityClass", Picture.class);
        ReflectionTestUtils.setField(service, "spaceService", spaceService);
        ReflectionTestUtils.setField(service, "cosManager", cos);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(new InMemoryTransactions()));
    }

    @Test
    void successfulDeletionReleasesQuotaAndCleansEachObjectOnce() {
        service.deletePicture(ID, user);
        assertFalse(pictures.containsKey(ID));
        assertEquals(1L, space.getTotalCount());
        assertEquals(60L, space.getTotalSize());
        assertEquals(1, cleanupCalls.get());
        assertEquals(Arrays.asList("public/original.webp", "public/thumbnail.webp"), cloudAttempts);
    }

    @Test
    void anyLiveOriginalReferencePreventsDeletingThatObject() {
        pictures.get(121L).setUrl(ORIGINAL_URL);
        service.deletePicture(ID, user);
        assertEquals(Collections.singletonList("public/thumbnail.webp"), cloudAttempts);
    }

    @Test
    void thumbnailReferencesAlsoProtectTheOriginalObject() {
        pictures.get(121L).setThumbnailUrl(ORIGINAL_URL);
        service.deletePicture(ID, user);
        assertEquals(Collections.singletonList("public/thumbnail.webp"), cloudAttempts);
    }

    @Test
    void aLiveOriginalReferenceAlsoProtectsTheThumbnailObject() {
        pictures.get(121L).setUrl(THUMBNAIL_URL);
        service.deletePicture(ID, user);
        assertEquals(Collections.singletonList("public/original.webp"), cloudAttempts);
    }

    @Test
    void identicalOriginalAndThumbnailKeysAreDeletedOnce() {
        pictures.get(ID).setThumbnailUrl(ORIGINAL_URL + "?preview=1");
        service.deletePicture(ID, user);
        assertEquals(Collections.singletonList("public/original.webp"), cloudAttempts);
    }

    @Test
    void decodedObjectKeyIsNotReinterpretedAsAnotherUrl() {
        pictures.get(ID).setUrl("https://owned.example.invalid/public/a%3A%2F%2Fb.webp");
        pictures.get(ID).setThumbnailUrl(null);
        service.deletePicture(ID, user);
        assertEquals(Collections.singletonList("public/a://b.webp"), cloudAttempts);
    }

    @Test
    void foreignFixtureUrlsNeverReachCloudDeletion() {
        pictures.get(ID).setUrl("https://fixture.example.invalid/original.webp");
        pictures.get(ID).setThumbnailUrl("https://fixture.example.invalid/thumbnail.webp");
        service.deletePicture(ID, user);
        assertTrue(cloudAttempts.isEmpty());
        assertEquals(1L, space.getTotalCount());
    }

    @Test
    void optionalCloudFailureDoesNotChangeSuccessfulDatabaseDeletion() {
        cloudFails = true;
        assertDoesNotThrow(() -> service.deletePicture(ID, user));
        assertFalse(pictures.containsKey(ID));
        assertEquals(1L, space.getTotalCount());
        assertEquals(60L, space.getTotalSize());
        assertEquals(2, cloudAttempts.size());
    }

    @Test
    void failedReferenceLookupNeverDeletesAnObjectOrFailsTheCommittedDelete() {
        referenceLookupFails = true;
        assertDoesNotThrow(() -> service.deletePicture(ID, user));
        assertFalse(pictures.containsKey(ID));
        assertTrue(cloudAttempts.isEmpty());
        assertEquals(60L, space.getTotalSize());
    }

    @Test
    void quotaFailureRollsBackDeletionAndSkipsCleanup() {
        quotaFails = true;
        assertThrows(BusinessException.class, () -> service.deletePicture(ID, user));
        assertTrue(pictures.containsKey(ID));
        assertEquals(2L, space.getTotalCount());
        assertEquals(100L, space.getTotalSize());
        assertEquals(0, cleanupCalls.get());
        assertTrue(cloudAttempts.isEmpty());
    }

    @Test
    void failedPictureRemovalDoesNotReleaseQuotaOrCleanObjects() {
        removeFails = true;
        assertThrows(BusinessException.class, () -> service.deletePicture(ID, user));
        assertEquals(2L, space.getTotalCount());
        assertEquals(100L, space.getTotalSize());
        assertEquals(0, cleanupCalls.get());
    }

    @Test
    void deletionUsesTheCurrentLockedSizeAfterAnOverlappingReplacement() {
        staleInitialPicture = copy(pictures.get(ID));
        pictures.get(ID).setPicSize(60L);
        space.setTotalSize(120L);
        service.deletePicture(ID, user);
        assertEquals(60L, space.getTotalSize());
        assertEquals(1L, space.getTotalCount());
    }

    @Test
    void repeatedDeletionDoesNotReleaseQuotaOrCleanObjectsAgain() {
        service.deletePicture(ID, user);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.deletePicture(ID, user));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), failure.getCode());
        assertEquals(1L, space.getTotalCount());
        assertEquals(60L, space.getTotalSize());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void concurrentDeletionOnlyReleasesQuotaAndCleansOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(executor.submit(() -> {
                    try {
                        service.deletePicture(ID, user);
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    }
                }));
            }
            int successes = (calls.get(0).get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (calls.get(1).get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1L, space.getTotalCount());
            assertEquals(60L, space.getTotalSize());
            assertEquals(1, cleanupCalls.get());
            assertEquals(2, cloudAttempts.size());
        } finally {
            executor.shutdownNow();
        }
    }

    private int releaseQuota(LambdaUpdateWrapper<Space> update) {
        if (quotaFails) {
            return 0;
        }
        update.getSqlSegment();
        long count = removedAmount(update.getSqlSet(), "totalCount", update.getParamNameValuePairs());
        long size = removedAmount(update.getSqlSet(), "totalSize", update.getParamNameValuePairs());
        synchronized (space) {
            space.setTotalCount(space.getTotalCount() - count);
            space.setTotalSize(space.getTotalSize() - size);
        }
        transaction.get().count += count;
        transaction.get().size += size;
        return 1;
    }

    private long removedAmount(String sql, String column, Map<String, Object> parameters) {
        Matcher matcher = Pattern.compile(column + "\\s*=\\s*" + column + "\\s*-\\s*(#\\{[^}]+}|\\d+)").matcher(sql);
        if (!matcher.find()) {
            return 0;
        }
        String value = matcher.group(1);
        return value.startsWith("#{") ? ((Number) parameters.get(value.substring(value.lastIndexOf('.') + 1, value.length() - 1))).longValue()
                : Long.parseLong(value);
    }

    private Picture picture(long id, String url, String thumbnail, long size) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setSpaceId(SPACE_ID);
        picture.setUserId(11L);
        picture.setUrl(url);
        picture.setThumbnailUrl(thumbnail);
        picture.setPicSize(size);
        return picture;
    }

    private Picture copy(Picture picture) {
        if (picture == null) {
            return null;
        }
        Picture copy = new Picture();
        BeanUtils.copyProperties(picture, copy);
        return copy;
    }

    private class RecordingDeletionService extends PictureServiceImpl {
        @Override
        public boolean removeById(Serializable id) {
            if (removeFails) {
                return false;
            }
            Picture removed = pictures.remove(id);
            if (removed != null) {
                transaction.get().removed.put((Long) id, removed);
            }
            return removed != null;
        }

        @Override
        public void clearPictureFile(Picture picture) {
            cleanupCalls.incrementAndGet();
            super.clearPictureFile(picture);
        }
    }

    private static class Changes {
        private long count;
        private long size;
        private final Map<Long, Picture> removed = new HashMap<>();
        private final List<ReentrantLock> locks = new ArrayList<>();
    }

    private class InMemoryTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object ignored, TransactionDefinition definition) { transaction.set(new Changes()); }
        @Override protected void doCommit(DefaultTransactionStatus status) { finish(); }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            Changes changes = transaction.get();
            synchronized (space) {
                space.setTotalCount(space.getTotalCount() + changes.count);
                space.setTotalSize(space.getTotalSize() + changes.size);
            }
            pictures.putAll(changes.removed);
            finish();
        }
        private void finish() {
            transaction.get().locks.forEach(ReentrantLock::unlock);
            transaction.remove();
        }
    }
}
