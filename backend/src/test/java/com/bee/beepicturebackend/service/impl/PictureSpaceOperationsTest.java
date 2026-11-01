package com.bee.beepicturebackend.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.manager.auth.SpaceUserAuthManager;
import com.bee.beepicturebackend.mapper.PictureMapper;
import com.bee.beepicturebackend.mapper.SpaceUserMapper;
import com.bee.beepicturebackend.model.dto.picture.PictureEditByBatchRequest;
import com.bee.beepicturebackend.model.dto.picture.SearchPictureByColorRequest;
import com.bee.beepicturebackend.model.entity.Picture;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.SpaceUser;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.model.vo.PictureVO;
import com.bee.beepicturebackend.service.SpaceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PictureSpaceOperationsTest {

    private static final long SPACE_ID = 100L;
    private static final long USER_ID = 11L;
    private RecordingPictureService service;
    private User user;
    private Space space;
    private SpaceUser membership;
    private final List<Picture> databasePictures = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Picture.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SpaceUser.class);
        user = new User();
        user.setId(USER_ID);
        user.setUserRole("user");
        space = new Space();
        space.setId(SPACE_ID);
        space.setUserId(22L);
        space.setSpaceType(1);
        SpaceService spaces = mock(SpaceService.class);
        when(spaces.getById(SPACE_ID)).thenReturn(space);

        SpaceUserMapper memberMapper = mock(SpaceUserMapper.class);
        when(memberMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            Map<String, Object> parameters = query.getParamNameValuePairs();
            return membership != null && parameters.containsValue(SPACE_ID) && parameters.containsValue(USER_ID)
                    ? membership : null;
        });
        SpaceUserServiceImpl members = new SpaceUserServiceImpl();
        ReflectionTestUtils.setField(members, "baseMapper", memberMapper);
        ReflectionTestUtils.setField(members, "entityClass", SpaceUser.class);
        SpaceUserAuthManager permissions = new SpaceUserAuthManager();
        ReflectionTestUtils.setField(permissions, "spaceUserService", members);
        ReflectionTestUtils.setField(permissions, "userService", new UserServiceImpl());

        PictureMapper pictures = mock(PictureMapper.class);
        when(pictures.selectList(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment().replace("_", "").toLowerCase();
            Collection<Object> parameters = query.getParamNameValuePairs().values();
            return databasePictures.stream()
                    .filter(picture -> !sql.contains("spaceid =") || parameters.contains(picture.getSpaceId()))
                    .filter(picture -> !sql.contains(" in ") || parameters.contains(picture.getId()))
                    .collect(Collectors.toList());
        });
        service = new RecordingPictureService();
        ReflectionTestUtils.setField(service, "baseMapper", pictures);
        ReflectionTestUtils.setField(service, "entityClass", Picture.class);
        ReflectionTestUtils.setField(service, "spaceService", spaces);
        ReflectionTestUtils.setField(service, "spaceUserAuthManager", permissions);
        databasePictures.clear();
        databasePictures.add(picture(120L, SPACE_ID, "#ff0000"));
        databasePictures.add(picture(121L, SPACE_ID, "#0000ff"));
        databasePictures.add(picture(220L, 200L, "#ff0000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "editor", "admin"})
    void teamMembersCanSearchColorsWithinTheirActualSpace(String role) {
        asMember(role);
        List<PictureVO> result = assertDoesNotThrow(() -> service.searchPictureByColor(colorRequest(), user));
        assertEquals(Arrays.asList(120L, 121L), result.stream().map(PictureVO::getId).collect(Collectors.toList()));
    }

    @Test
    void nonMemberCannotSearchTeamColors() {
        assertDenied(() -> service.searchPictureByColor(colorRequest(), user));
    }

    @Test
    void privateSpaceUploaderWithoutSpaceOwnershipCannotSearchColors() {
        space.setSpaceType(0);
        databasePictures.get(0).setUserId(USER_ID);
        assertDenied(() -> service.searchPictureByColor(colorRequest(), user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"editor", "admin"})
    void teamEditorsCanBatchEditTheirSpacePictures(String role) {
        asMember(role);
        assertDoesNotThrow(() -> service.editPictureByBatch(batchRequest(120L, 121L), user));
        assertEquals(2, service.saved.size());
        assertEquals("图片1", service.saved.get(0).getName());
        assertEquals("图片2", service.saved.get(1).getName());
        assertEquals("素材", service.saved.get(0).getCategory());
        assertEquals("[\"团队\"]", service.saved.get(0).getTags());
    }

    @Test
    void viewerCannotBatchEditEvenTheirOwnUploads() {
        asMember("viewer");
        databasePictures.get(0).setUserId(USER_ID);
        assertDenied(() -> service.editPictureByBatch(batchRequest(120L), user));
        assertTrue(service.saved.isEmpty());
    }

    @Test
    void teamOwnershipDoesNotOverrideAnActualViewerRole() {
        asMember("viewer");
        space.setUserId(USER_ID);
        assertDenied(() -> service.editPictureByBatch(batchRequest(120L), user));
        assertTrue(service.saved.isEmpty());
    }

    @Test
    void batchEditRejectsForeignIdsWithoutPartiallyUpdatingAllowedPictures() {
        // Owner access exists before the fix too, so this isolates the silent foreign-ID omission.
        space.setUserId(USER_ID);
        space.setSpaceType(0);
        assertDenied(() -> service.editPictureByBatch(batchRequest(120L, 220L), user));
        assertTrue(service.saved.isEmpty());
        assertEquals("original", databasePictures.get(0).getName());
    }

    @Test
    void privateOwnerCanStillBatchEditTheirPictures() {
        space.setUserId(USER_ID);
        space.setSpaceType(0);
        service.editPictureByBatch(batchRequest(120L), user);
        assertEquals("图片1", service.saved.get(0).getName());
    }

    @Test
    void duplicateBatchIdsUpdateThePictureOnce() {
        asMember("editor");
        service.editPictureByBatch(batchRequest(120L, 120L), user);
        assertEquals(1, service.saved.size());
        assertEquals("图片1", service.saved.get(0).getName());
    }

    private SearchPictureByColorRequest colorRequest() {
        SearchPictureByColorRequest request = new SearchPictureByColorRequest();
        request.setSpaceId(SPACE_ID);
        request.setPicColor("#ff0000");
        return request;
    }

    private PictureEditByBatchRequest batchRequest(Long... ids) {
        PictureEditByBatchRequest request = new PictureEditByBatchRequest();
        request.setSpaceId(SPACE_ID);
        request.setPictureIdList(Arrays.asList(ids));
        request.setNameRule("图片{序号}");
        request.setCategory("素材");
        request.setTags(Arrays.asList("团队"));
        return request;
    }

    private void asMember(String role) {
        membership = new SpaceUser();
        membership.setSpaceId(SPACE_ID);
        membership.setUserId(USER_ID);
        membership.setSpaceRole(role);
    }

    private void assertDenied(Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    private Picture picture(long id, long spaceId, String color) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setSpaceId(spaceId);
        picture.setUserId(22L);
        picture.setPicColor(color);
        picture.setTags("[]");
        picture.setName("original");
        return picture;
    }

    private static class RecordingPictureService extends PictureServiceImpl {
        private final List<Picture> saved = new ArrayList<>();

        @Override
        public boolean updateBatchById(Collection<Picture> pictures) {
            saved.addAll(pictures);
            return true;
        }
    }
}
