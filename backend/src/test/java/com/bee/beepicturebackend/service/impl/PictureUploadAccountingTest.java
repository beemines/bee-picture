package com.bee.beepicturebackend.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.config.CosClientConfig;
import com.bee.beepicturebackend.manager.CosManager;
import com.bee.beepicturebackend.manager.upload.FilePictureUpload;
import com.bee.beepicturebackend.manager.upload.UrlPictureUpload;
import com.bee.beepicturebackend.mapper.PictureMapper;
import com.bee.beepicturebackend.mapper.SpaceMapper;
import com.bee.beepicturebackend.model.dto.file.UploadPictureResult;
import com.bee.beepicturebackend.model.dto.picture.PictureUploadRequest;
import com.bee.beepicturebackend.model.entity.Picture;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.User;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

/** In-memory database boundaries execute quota predicates against current, shared counters. */
class PictureUploadAccountingTest {

    private static final long SPACE_ID = 100L;
    private static final long PICTURE_ID = 120L;
    private static final Object INPUT = new Object();
    private final Map<Long, Picture> pictures = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> pictureLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<TxChanges> transaction = new ThreadLocal<>();
    private final AtomicLong nextId = new AtomicLong(1000L);
    private final AtomicInteger uploadCalls = new AtomicInteger();
    private Space space;
    private User user;
    private RecordingUploadService service;
    private UploadPictureResult uploaded;
    private boolean spaceExists;
    private boolean saveFails;
    private volatile Runnable duringUpload;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Picture.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Space.class);
        pictures.clear();
        pictureLocks.clear();
        uploadCalls.set(0);
        spaceExists = true;
        saveFails = false;
        duringUpload = () -> { };
        space = new Space();
        space.setId(SPACE_ID);
        space.setSpaceType(0);
        space.setUserId(11L);
        space.setTotalCount(5L);
        space.setTotalSize(100L);
        space.setMaxCount(10L);
        space.setMaxSize(500L);
        user = new User();
        user.setId(11L);
        user.setUserRole("user");
        Picture original = new Picture();
        original.setId(PICTURE_ID);
        original.setSpaceId(SPACE_ID);
        original.setUserId(22L);
        original.setPicSize(40L);
        original.setName("原始名称");
        original.setCategory("原始分类");
        original.setTags("[\"保留标签\"]");
        original.setIntroduction("保留简介");
        original.setCreateTime(new Date(123456789L));
        original.setReviewStatus(1);
        pictures.put(PICTURE_ID, original);

        uploaded = new UploadPictureResult();
        uploaded.setUrl("https://example.invalid/new.webp");
        uploaded.setThumbnailUrl("https://example.invalid/new-thumb.webp");
        uploaded.setPicName("crop.png");
        uploaded.setPicSize(60L);
        uploaded.setPicColor("0xff0000");
        uploaded.setPicWidth(20);
        uploaded.setPicHeight(10);
        uploaded.setPicScale(2.0);
        uploaded.setPicFormat("webp");
        FilePictureUpload files = new FilePictureUpload() {
            @Override protected void validPicture(Object source) { }
            @Override protected String getOriginFilename(Object source) { return "crop.png"; }
            @Override protected void processFile(Object source, File file) throws Exception {
                Files.write(file.toPath(), new byte[]{1});
            }
        };
        UrlPictureUpload urls = new UrlPictureUpload() {
            @Override protected void validPicture(Object source) { }
            @Override protected String getOriginFilename(Object source) { return "crop.png"; }
            @Override protected void processFile(Object source, File file) throws Exception {
                Files.write(file.toPath(), new byte[]{1});
            }
        };
        CosManager cos = mock(CosManager.class);
        when(cos.putPictureObject(any(), any())).thenAnswer(invocation -> uploadedResult());
        CosClientConfig configuration = new CosClientConfig();
        configuration.setHost("https://example.invalid");
        ReflectionTestUtils.setField(files, "cosManager", cos);
        ReflectionTestUtils.setField(files, "cosClientConfig", configuration);
        ReflectionTestUtils.setField(urls, "cosManager", cos);
        ReflectionTestUtils.setField(urls, "cosClientConfig", configuration);

        SpaceMapper spaces = mock(SpaceMapper.class);
        when(spaces.selectById(SPACE_ID)).thenAnswer(invocation -> spaceExists ? copy(space) : null);
        when(spaces.update(any(), any())).thenAnswer(invocation -> applyQuotaUpdate(invocation.getArgument(1)));
        SpaceServiceImpl spaceService = new SpaceServiceImpl();
        ReflectionTestUtils.setField(spaceService, "baseMapper", spaces);
        ReflectionTestUtils.setField(spaceService, "entityClass", Space.class);

        PictureMapper pictureMapper = mock(PictureMapper.class);
        when(pictureMapper.selectById(any())).thenAnswer(invocation -> copy(pictures.get(invocation.getArgument(0))));
        when(pictureMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment();
            Long id = query.getParamNameValuePairs().values().stream()
                    .filter(value -> value instanceof Long).map(value -> (Long) value).findFirst().orElse(null);
            if (sql.toUpperCase().contains("FOR UPDATE")) {
                ReentrantLock lock = pictureLocks.computeIfAbsent(id, ignored -> new ReentrantLock());
                lock.lock();
                transaction.get().locks.add(lock);
            }
            return copy(pictures.get(id));
        });
        service = new RecordingUploadService();
        ReflectionTestUtils.setField(service, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(service, "entityClass", Picture.class);
        ReflectionTestUtils.setField(service, "spaceService", spaceService);
        ReflectionTestUtils.setField(service, "userService", new UserServiceImpl());
        ReflectionTestUtils.setField(service, "filePictureUpload", files);
        ReflectionTestUtils.setField(service, "urlPictureUpload", urls);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(new InMemoryTransactions()));
    }

    @Test
    void replacementKeepsCountAndUsesOnlyTheSizeDelta() {
        service.uploadPicture(INPUT, replacement(), user);
        assertEquals(5L, space.getTotalCount());
        assertEquals(120L, space.getTotalSize());
        assertEquals(60L, pictures.get(PICTURE_ID).getPicSize());
    }

    @Test
    void replacementResolvesItsActualSpaceWhenTheDtoOmitsIt() {
        PictureUploadRequest request = replacement();
        request.setSpaceId(null);
        service.uploadPicture(INPUT, request, user);
        assertEquals(5L, space.getTotalCount());
        assertEquals(120L, space.getTotalSize());
    }

    @Test
    void replacementIsAllowedAtThePictureCountLimit() {
        space.setMaxCount(5L);
        uploaded.setPicSize(30L);
        assertDoesNotThrow(() -> service.uploadPicture(INPUT, replacement(), user));
        assertEquals(5L, space.getTotalCount());
        assertEquals(90L, space.getTotalSize());
    }

    @Test
    void shrinkingReplacementIsAllowedAtTheSizeLimit() {
        space.setMaxSize(100L);
        uploaded.setPicSize(30L);
        assertDoesNotThrow(() -> service.uploadPicture(INPUT, replacement(), user));
        assertEquals(90L, space.getTotalSize());
    }

    @Test
    void equalSizeReplacementIsAllowedAtBothLimits() {
        space.setMaxCount(5L);
        space.setMaxSize(100L);
        uploaded.setPicSize(40L);
        assertDoesNotThrow(() -> service.uploadPicture(INPUT, replacement(), user));
        assertEquals(5L, space.getTotalCount());
        assertEquals(100L, space.getTotalSize());
    }

    @Test
    void growingReplacementCannotExceedTheRemainingSize() {
        space.setTotalSize(95L);
        space.setMaxSize(100L);
        uploaded.setPicSize(50L);
        assertQuotaFailure(() -> service.uploadPicture(INPUT, replacement(), user));
        assertEquals(95L, space.getTotalSize());
        assertEquals(40L, pictures.get(PICTURE_ID).getPicSize());
    }

    @Test
    void newUploadIsRejectedBeforeCloudUploadWhenTheCountIsFull() {
        space.setMaxCount(5L);
        assertQuotaFailure(() -> service.uploadPicture(INPUT, newUpload(), user));
        assertEquals(0, uploadCalls.get());
        assertEquals(5L, space.getTotalCount());
    }

    @Test
    void newUploadCanUseExactlyTheRemainingSize() {
        space.setTotalSize(90L);
        space.setMaxSize(100L);
        uploaded.setPicSize(10L);
        service.uploadPicture(INPUT, newUpload(), user);
        assertEquals(6L, space.getTotalCount());
        assertEquals(100L, space.getTotalSize());
    }

    @Test
    void newUploadCannotExceedTheRemainingSize() {
        space.setTotalSize(90L);
        space.setMaxSize(100L);
        uploaded.setPicSize(11L);
        assertQuotaFailure(() -> service.uploadPicture(INPUT, newUpload(), user));
        assertEquals(5L, space.getTotalCount());
        assertEquals(90L, space.getTotalSize());
        assertEquals(1, pictures.size());
    }

    @Test
    void concurrentNewUploadsCannotConsumeTheSameCountSlot() throws Exception {
        concurrentNewUploads(1L, 100L);
        assertEquals(1L, space.getTotalCount());
        assertEquals(10L, space.getTotalSize());
        assertEquals(1, pictures.size());
    }

    @Test
    void concurrentNewUploadsCannotExceedTheSharedSizeLimit() throws Exception {
        concurrentNewUploads(10L, 15L);
        assertEquals(1L, space.getTotalCount());
        assertEquals(10L, space.getTotalSize());
        assertEquals(1, pictures.size());
    }

    @Test
    void overlappingReplacementsUseTheLatestLockedPictureSize() throws Exception {
        List<Boolean> outcomes = concurrentUploads(true);
        assertEquals(Arrays.asList(true, true), outcomes);
        assertEquals(5L, space.getTotalCount());
        assertEquals(120L, space.getTotalSize());
        assertEquals(60L, pictures.get(PICTURE_ID).getPicSize());
    }

    @Test
    void concurrentReplacementGrowthCannotExceedTheSharedSizeLimit() throws Exception {
        Picture secondPicture = copy(pictures.get(PICTURE_ID));
        secondPicture.setId(121L);
        pictures.put(121L, secondPicture);
        space.setTotalCount(2L);
        space.setMaxCount(2L);
        space.setTotalSize(80L);
        space.setMaxSize(100L);
        List<Boolean> outcomes = concurrentUploads(true, PICTURE_ID, 121L);
        assertEquals(1L, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(2L, space.getTotalCount());
        assertEquals(100L, space.getTotalSize());
        assertEquals(100L, pictures.values().stream().mapToLong(Picture::getPicSize).sum());
    }

    @Test
    void replacementPreservesTheOriginalAuthorAndOmittedMetadata() {
        service.uploadPicture(INPUT, replacement(), user);
        Picture saved = pictures.get(PICTURE_ID);
        assertEquals(22L, saved.getUserId());
        assertEquals("原始名称", saved.getName());
        assertEquals("原始分类", saved.getCategory());
        assertEquals("[\"保留标签\"]", saved.getTags());
        assertEquals("保留简介", saved.getIntroduction());
        assertEquals(new Date(123456789L), saved.getCreateTime());
        assertEquals("https://example.invalid/new.webp", saved.getUrl());
        assertNotNull(saved.getEditTime());
    }

    @Test
    void replacementAppliesExplicitMetadataIncludingClearingTags() {
        PictureUploadRequest request = replacement();
        request.setPicName("裁剪后名称");
        request.setCategory("");
        request.setTags(Collections.emptyList());
        service.uploadPicture(INPUT, request, user);
        Picture saved = pictures.get(PICTURE_ID);
        assertEquals(22L, saved.getUserId());
        assertEquals("裁剪后名称", saved.getName());
        assertEquals("", saved.getCategory());
        assertEquals("[]", saved.getTags());
    }

    @Test
    void failedPicturePersistenceRollsBackTheQuotaReservation() {
        saveFails = true;
        assertQuotaFailure(() -> service.uploadPicture(INPUT, replacement(), user));
        assertEquals(5L, space.getTotalCount());
        assertEquals(100L, space.getTotalSize());
        assertEquals(40L, pictures.get(PICTURE_ID).getPicSize());
    }

    @Test
    void omittedTagsOnANewUploadBecomeAnEmptyArray() {
        Long id = service.uploadPicture(INPUT, newUpload(), user).getId();
        assertEquals("[]", pictures.get(id).getTags());
        assertEquals(11L, pictures.get(id).getUserId());
    }

    @Test
    void omittedSpaceIdStillValidatesTheActualSpaceBeforeUploading() {
        spaceExists = false;
        PictureUploadRequest request = replacement();
        request.setSpaceId(null);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.uploadPicture(INPUT, request, user));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), failure.getCode());
        assertEquals(0, uploadCalls.get());
    }

    @Test
    void replacementCannotMoveThePictureToAnotherSpace() {
        PictureUploadRequest request = replacement();
        request.setSpaceId(200L);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.uploadPicture(INPUT, request, user));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), failure.getCode());
        assertEquals(0, uploadCalls.get());
    }

    private void concurrentNewUploads(long countLimit, long sizeLimit) throws Exception {
        pictures.clear();
        space.setTotalCount(0L);
        space.setTotalSize(0L);
        space.setMaxCount(countLimit);
        space.setMaxSize(sizeLimit);
        uploaded.setPicSize(10L);
        List<Boolean> outcomes = concurrentUploads(false);
        assertEquals(1L, outcomes.stream().filter(Boolean::booleanValue).count());
    }

    private List<Boolean> concurrentUploads(boolean replacing, Long... ids) throws Exception {
        CountDownLatch bothUploaded = new CountDownLatch(2);
        duringUpload = () -> {
            bothUploaded.countDown();
            try {
                assertTrue(bothUploaded.await(5, TimeUnit.SECONDS), "Both calls must observe the pre-transaction quota state");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                PictureUploadRequest request = replacing ? replacement() : newUpload();
                if (ids.length > 0) {
                    request.setId(ids[i]);
                }
                tasks.add(executor.submit(() -> {
                    try {
                        service.uploadPicture(INPUT, request, user);
                        return true;
                    } catch (BusinessException e) {
                        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), e.getCode());
                        return false;
                    }
                }));
            }
            return Arrays.asList(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private PutObjectResult uploadedResult() {
        uploadCalls.incrementAndGet();
        duringUpload.run();
        CIObject compressed = new CIObject();
        compressed.setKey("new.webp");
        compressed.setWidth(uploaded.getPicWidth());
        compressed.setHeight(uploaded.getPicHeight());
        compressed.setSize(uploaded.getPicSize().intValue());
        compressed.setFormat(uploaded.getPicFormat());
        PutObjectResult result = mock(PutObjectResult.class, RETURNS_DEEP_STUBS);
        when(result.getCiUploadResult().getOriginalInfo().getImageInfo().getAve()).thenReturn(uploaded.getPicColor());
        when(result.getCiUploadResult().getProcessResults().getObjectList()).thenReturn(Collections.singletonList(compressed));
        return result;
    }

    private PictureUploadRequest replacement() {
        PictureUploadRequest request = newUpload();
        request.setId(PICTURE_ID);
        return request;
    }

    private PictureUploadRequest newUpload() {
        PictureUploadRequest request = new PictureUploadRequest();
        request.setSpaceId(SPACE_ID);
        return request;
    }

    private void assertQuotaFailure(Runnable action) {
        BusinessException failure = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), failure.getCode());
    }

    private int applyQuotaUpdate(LambdaUpdateWrapper<Space> update) {
        String set = update.getSqlSet();
        String where = update.getSqlSegment().replaceAll("\\s+", " ");
        Map<String, Object> parameters = update.getParamNameValuePairs();
        long countDelta = assignmentDelta(set, "totalCount", parameters);
        long sizeDelta = assignmentDelta(set, "totalSize", parameters);
        synchronized (space) {
            if ((where.contains("totalCount +") && where.contains("<= maxCount")
                    && space.getTotalCount() + countDelta > space.getMaxCount())
                    || (where.contains("totalSize +") && where.contains("<= maxSize")
                    && space.getTotalSize() + sizeDelta > space.getMaxSize())) {
                return 0;
            }
            space.setTotalCount(space.getTotalCount() + countDelta);
            space.setTotalSize(space.getTotalSize() + sizeDelta);
            transaction.get().countDelta += countDelta;
            transaction.get().sizeDelta += sizeDelta;
            return 1;
        }
    }

    private long assignmentDelta(String sql, String column, Map<String, Object> parameters) {
        Matcher expression = Pattern.compile(column + "\\s*=\\s*" + column + "\\s*\\+\\s*(#\\{[^}]+}|-?\\d+)").matcher(sql);
        if (!expression.find()) {
            return 0L;
        }
        String value = expression.group(1);
        if (value.startsWith("#{")) {
            String key = value.substring(value.lastIndexOf('.') + 1, value.length() - 1);
            return ((Number) parameters.get(key)).longValue();
        }
        return Long.parseLong(value);
    }

    private Picture copy(Picture original) {
        if (original == null) {
            return null;
        }
        Picture copy = new Picture();
        BeanUtils.copyProperties(original, copy);
        return copy;
    }

    private Space copy(Space original) {
        synchronized (original) {
            Space copy = new Space();
            BeanUtils.copyProperties(original, copy);
            return copy;
        }
    }

    private class RecordingUploadService extends PictureServiceImpl {
        @Override
        public boolean saveOrUpdate(Picture picture) {
            if (saveFails) {
                return false;
            }
            if (picture.getId() == null) {
                picture.setId(nextId.incrementAndGet());
            }
            transaction.get().previousPictures.put(picture.getId(), copy(pictures.get(picture.getId())));
            pictures.put(picture.getId(), copy(picture));
            return true;
        }
    }

    private static class TxChanges {
        private long countDelta;
        private long sizeDelta;
        private final Map<Long, Picture> previousPictures = new HashMap<>();
        private final List<ReentrantLock> locks = new ArrayList<>();
    }

    private class InMemoryTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object ignored, TransactionDefinition definition) {
            transaction.set(new TxChanges());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            finish();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            TxChanges changes = transaction.get();
            synchronized (space) {
                space.setTotalCount(space.getTotalCount() - changes.countDelta);
                space.setTotalSize(space.getTotalSize() - changes.sizeDelta);
            }
            changes.previousPictures.forEach((id, previous) -> {
                if (previous == null) {
                    pictures.remove(id);
                } else {
                    pictures.put(id, previous);
                }
            });
            finish();
        }

        private void finish() {
            transaction.get().locks.forEach(ReentrantLock::unlock);
            transaction.remove();
        }
    }
}
