package com.bee.beepicturebackend.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.manager.auth.SpaceUserAuthManager;
import com.bee.beepicturebackend.mapper.PictureMapper;
import com.bee.beepicturebackend.mapper.SpaceMapper;
import com.bee.beepicturebackend.mapper.SpaceUserMapper;
import com.bee.beepicturebackend.model.dto.space.analyze.*;
import com.bee.beepicturebackend.model.entity.Picture;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.SpaceUser;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.model.vo.space.analyze.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpaceAnalyzePermissionTest {

    private static final long SPACE_ID = 100L;
    private static final long USER_ID = 11L;
    private static final List<String> ANALYSES = Arrays.asList("usage", "category", "tag", "size", "user");
    private SpaceAnalyzeServiceImpl service;
    private SpaceUserAuthManager permissions;
    private Space space;
    private User user;
    private SpaceUser membership;
    private final List<Picture> pictures = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SpaceUser.class);
        space = new Space();
        space.setId(SPACE_ID);
        space.setUserId(22L);
        space.setSpaceType(1);
        space.setTotalSize(3000L);
        space.setMaxSize(6000L);
        space.setTotalCount(2L);
        space.setMaxCount(4L);
        user = new User();
        user.setId(USER_ID);
        user.setUserRole("user");

        UserServiceImpl users = new UserServiceImpl();
        SpaceMapper spaces = mock(SpaceMapper.class);
        when(spaces.selectById(SPACE_ID)).thenReturn(space);
        when(spaces.selectList(any())).thenReturn(Collections.singletonList(space));
        SpaceServiceImpl spaceService = new SpaceServiceImpl();
        ReflectionTestUtils.setField(spaceService, "baseMapper", spaces);
        ReflectionTestUtils.setField(spaceService, "entityClass", Space.class);
        ReflectionTestUtils.setField(spaceService, "userService", users);

        SpaceUserMapper members = mock(SpaceUserMapper.class);
        when(members.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return membership != null && query.getParamNameValuePairs().containsValue(SPACE_ID)
                    && query.getParamNameValuePairs().containsValue(USER_ID) ? membership : null;
        });
        SpaceUserServiceImpl memberService = new SpaceUserServiceImpl();
        ReflectionTestUtils.setField(memberService, "baseMapper", members);
        ReflectionTestUtils.setField(memberService, "entityClass", SpaceUser.class);
        permissions = new SpaceUserAuthManager();
        ReflectionTestUtils.setField(permissions, "spaceUserService", memberService);
        ReflectionTestUtils.setField(permissions, "userService", users);

        pictures.clear();
        pictures.add(picture(SPACE_ID, 1000L, "团队", "[\"红\",\"公用\"]"));
        pictures.add(picture(SPACE_ID, 2000L, "团队", "[\"公用\"]"));
        pictures.add(picture(200L, 9000L, "其他", "[\"外部\"]"));
        pictures.add(picture(null, 500L, "公开", "[\"公开\"]"));
        PictureMapper pictureMapper = mock(PictureMapper.class);
        when(pictureMapper.selectObjs(any())).thenAnswer(invocation -> {
            QueryWrapper<Picture> query = invocation.getArgument(0);
            return scopedPictures(query).stream().map(picture -> "tags".equals(query.getSqlSelect())
                    ? picture.getTags() : picture.getPicSize()).collect(Collectors.toList());
        });
        when(pictureMapper.selectMaps(any())).thenAnswer(invocation -> {
            QueryWrapper<Picture> query = invocation.getArgument(0);
            List<Picture> scoped = scopedPictures(query);
            if (query.getSqlSelect().contains("period")) {
                return Collections.singletonList(Map.of("period", "2026-11-01", "count", (long) scoped.size()));
            }
            Map<String, List<Picture>> categories = scoped.stream().collect(
                    Collectors.groupingBy(Picture::getCategory, LinkedHashMap::new, Collectors.toList()));
            return categories.entrySet().stream().map(entry -> Map.of("category", entry.getKey(),
                    "count", (long) entry.getValue().size(), "totalSize", entry.getValue().stream().mapToLong(Picture::getPicSize).sum()))
                    .collect(Collectors.toList());
        });
        PictureServiceImpl pictureService = new PictureServiceImpl();
        ReflectionTestUtils.setField(pictureService, "baseMapper", pictureMapper);
        service = new SpaceAnalyzeServiceImpl();
        ReflectionTestUtils.setField(service, "spaceService", spaceService);
        ReflectionTestUtils.setField(service, "pictureService", pictureService);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "spaceUserAuthManager", permissions);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("spaceRoleCases")
    void analyticsUseTheActualSpaceRole(String analysis, String identity, boolean allowed) {
        asIdentity(identity);
        SpaceAnalyzeRequest request = request(analysis);
        if (allowed) {
            Object result = assertDoesNotThrow(() -> analyze(analysis, request));
            assertResult(analysis, result, "space");
        } else {
            assertDenied(() -> analyze(analysis, request));
        }
    }

    static Stream<Arguments> spaceRoleCases() {
        return ANALYSES.stream().flatMap(analysis -> Stream.of(
                Arguments.of(analysis, "team_admin", true),
                Arguments.of(analysis, "team_viewer", false),
                Arguments.of(analysis, "team_editor", false),
                Arguments.of(analysis, "team_outsider", false),
                Arguments.of(analysis, "team_owner_viewer", false),
                Arguments.of(analysis, "team_owner_outsider", false),
                Arguments.of(analysis, "private_owner", true),
                Arguments.of(analysis, "private_other", false),
                Arguments.of(analysis, "private_global_admin", true),
                Arguments.of(analysis, "team_global_admin", true)));
    }

    @ParameterizedTest(name = "{0} / queryAll={1} / globalAdmin={2}")
    @MethodSource("globalScopeCases")
    void publicAndAllQueriesRemainGlobalAdminOnly(String analysis, boolean queryAll, boolean globalAdmin) {
        asIdentity("team_admin");
        if (globalAdmin) {
            user.setUserRole("admin");
        }
        SpaceAnalyzeRequest request = request(analysis);
        request.setQueryAll(queryAll);
        request.setQueryPublic(!queryAll);
        if (globalAdmin) {
            Object result = assertDoesNotThrow(() -> analyze(analysis, request));
            assertResult(analysis, result, queryAll ? "all" : "public");
        } else {
            assertDenied(() -> analyze(analysis, request));
        }
    }

    static Stream<Arguments> globalScopeCases() {
        return ANALYSES.stream().flatMap(analysis -> Stream.of(
                Arguments.of(analysis, false, false), Arguments.of(analysis, true, false),
                Arguments.of(analysis, false, true), Arguments.of(analysis, true, true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"team_admin", "team_viewer", "team_editor", "team_outsider", "private_owner", "private_global_admin"})
    void rankingRemainsGlobalAdminOnly(String identity) {
        asIdentity(identity);
        if ("private_global_admin".equals(identity)) {
            assertEquals(Collections.singletonList(SPACE_ID), service.getSpaceRankAnalyze(new SpaceRankAnalyzeRequest(), user)
                    .stream().map(Space::getId).collect(Collectors.toList()));
        } else {
            assertDenied(() -> service.getSpaceRankAnalyze(new SpaceRankAnalyzeRequest(), user));
        }
    }

    private void asIdentity(String identity) {
        if (identity.startsWith("private")) {
            space.setSpaceType(0);
            space.setUserId("private_owner".equals(identity) ? USER_ID : 22L);
        } else if (identity.startsWith("team_owner")) {
            space.setUserId(USER_ID);
        }
        if (identity.endsWith("global_admin")) {
            user.setUserRole("admin");
        } else if (identity.endsWith("viewer") || identity.endsWith("editor") || "team_admin".equals(identity)) {
            membership = new SpaceUser();
            membership.setSpaceId(SPACE_ID);
            membership.setUserId(USER_ID);
            membership.setSpaceRole(identity.substring(identity.lastIndexOf('_') + 1));
        }
    }

    private SpaceAnalyzeRequest request(String analysis) {
        SpaceAnalyzeRequest request;
        switch (analysis) {
            case "usage": request = new SpaceUsageAnalyzeRequest(); break;
            case "category": request = new SpaceCategoryAnalyzeRequest(); break;
            case "tag": request = new SpaceTagAnalyzeRequest(); break;
            case "size": request = new SpaceSizeAnalyzeRequest(); break;
            case "user":
                SpaceUserAnalyzeRequest userRequest = new SpaceUserAnalyzeRequest();
                userRequest.setTimeDimension("day");
                request = userRequest;
                break;
            default: throw new AssertionError(analysis);
        }
        request.setSpaceId(SPACE_ID);
        return request;
    }

    private Object analyze(String analysis, SpaceAnalyzeRequest request) {
        switch (analysis) {
            case "usage": return service.getSpaceUsageAnalyze((SpaceUsageAnalyzeRequest) request, user);
            case "category": return service.getSpaceCategoryAnalyze((SpaceCategoryAnalyzeRequest) request, user);
            case "tag": return service.getSpaceTagAnalyze((SpaceTagAnalyzeRequest) request, user);
            case "size": return service.getSpaceSizeAnalyze((SpaceSizeAnalyzeRequest) request, user);
            case "user": return service.getSpaceUserAnalyze((SpaceUserAnalyzeRequest) request, user);
            default: throw new AssertionError(analysis);
        }
    }

    @SuppressWarnings("unchecked")
    private void assertResult(String analysis, Object result, String scope) {
        long count = "space".equals(scope) ? 2L : "all".equals(scope) ? 4L : 1L;
        long size = "space".equals(scope) ? 3000L : "all".equals(scope) ? 12500L : 500L;
        switch (analysis) {
            case "usage":
                SpaceUsageAnalyzeResponse usage = (SpaceUsageAnalyzeResponse) result;
                assertEquals(count, usage.getUsedCount());
                assertEquals(size, usage.getUsedSize());
                if ("space".equals(scope)) {
                    assertEquals(50.0, usage.getCountUsageRatio());
                    assertEquals(50.0, usage.getSizeUsageRatio());
                }
                break;
            case "category":
                List<SpaceCategoryAnalyzeResponse> categories = (List<SpaceCategoryAnalyzeResponse>) result;
                assertEquals(count, categories.stream().mapToLong(SpaceCategoryAnalyzeResponse::getCount).sum());
                assertEquals(size, categories.stream().mapToLong(SpaceCategoryAnalyzeResponse::getTotalSize).sum());
                assertEquals("public".equals(scope) ? "公开" : "团队", categories.get(0).getCategory());
                break;
            case "tag":
                Map<String, Long> tags = ((List<SpaceTagAnalyzeResponse>) result).stream()
                        .collect(Collectors.toMap(SpaceTagAnalyzeResponse::getTag, SpaceTagAnalyzeResponse::getCount));
                Map<String, Long> expected = "space".equals(scope) ? Map.of("公用", 2L, "红", 1L)
                        : "all".equals(scope) ? Map.of("公用", 2L, "红", 1L, "外部", 1L, "公开", 1L) : Map.of("公开", 1L);
                assertEquals(expected, tags);
                break;
            case "size":
                List<SpaceSizeAnalyzeResponse> sizes = (List<SpaceSizeAnalyzeResponse>) result;
                assertEquals("<500KB", sizes.get(0).getSizeRange());
                assertEquals(count, sizes.get(0).getCount());
                assertEquals(count, sizes.stream().mapToLong(SpaceSizeAnalyzeResponse::getCount).sum());
                break;
            case "user":
                List<SpaceUserAnalyzeResponse> users = (List<SpaceUserAnalyzeResponse>) result;
                assertEquals("2026-11-01", users.get(0).getPeriod());
                assertEquals(count, users.get(0).getCount());
                break;
            default: throw new AssertionError(analysis);
        }
    }

    private void assertDenied(Runnable action) {
        BusinessException failure = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), failure.getCode());
    }

    private List<Picture> scopedPictures(QueryWrapper<Picture> query) {
        String sql = query.getSqlSegment();
        if (sql.contains("spaceId IS NULL")) {
            return pictures.stream().filter(picture -> picture.getSpaceId() == null).collect(Collectors.toList());
        }
        if (sql.contains("spaceId =")) {
            return pictures.stream().filter(picture -> query.getParamNameValuePairs().containsValue(picture.getSpaceId()))
                    .collect(Collectors.toList());
        }
        return pictures;
    }

    private Picture picture(Long spaceId, long size, String category, String tags) {
        Picture picture = new Picture();
        picture.setSpaceId(spaceId);
        picture.setPicSize(size);
        picture.setCategory(category);
        picture.setTags(tags);
        return picture;
    }
}
