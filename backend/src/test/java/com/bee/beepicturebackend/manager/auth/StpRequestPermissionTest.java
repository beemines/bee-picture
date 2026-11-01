package com.bee.beepicturebackend.manager.auth;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.spring.SaTokenContextForSpring;
import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bee.beepicturebackend.mapper.PictureMapper;
import com.bee.beepicturebackend.mapper.SpaceUserMapper;
import com.bee.beepicturebackend.model.entity.Picture;
import com.bee.beepicturebackend.model.entity.Space;
import com.bee.beepicturebackend.model.entity.SpaceUser;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.service.SpaceService;
import com.bee.beepicturebackend.service.impl.PictureServiceImpl;
import com.bee.beepicturebackend.service.impl.SpaceUserServiceImpl;
import com.bee.beepicturebackend.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.bee.beepicturebackend.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StpRequestPermissionTest {

    private static final long USER_ID = 11L;
    private static final long PICTURE_ID = 120L;
    private static final long TARGET_SPACE_ID = 100L;
    private static final long OWN_SPACE_ID = 200L;
    private static final long TARGET_MEMBER_ID = 999L;
    private static final long OWN_MEMBER_ID = 888L;
    private static final List<String> ADMIN_PERMISSIONS = Arrays.asList(
            "spaceUser:manage", "picture:view", "picture:upload", "picture:edit", "picture:delete");

    private StpInterfaceImpl authorization;
    private MockHttpServletRequest request;
    private Picture picture;
    private Space targetSpace;
    private final Map<Long, SpaceUser> memberships = new HashMap<>();
    private SaTokenContext previousContext;
    private StpInterface previousStpInterface;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Picture.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), SpaceUser.class);
        picture = new Picture();
        picture.setId(PICTURE_ID);
        picture.setSpaceId(TARGET_SPACE_ID);
        picture.setUserId(22L);
        PictureMapper pictureMapper = mock(PictureMapper.class);
        when(pictureMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue(PICTURE_ID) ? picture : null;
        });
        PictureServiceImpl pictureService = new PictureServiceImpl();
        ReflectionTestUtils.setField(pictureService, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(pictureService, "entityClass", Picture.class);

        SpaceUserMapper memberMapper = mock(SpaceUserMapper.class);
        when(memberMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            Map<String, Object> parameters = query.getParamNameValuePairs();
            return memberships.values().stream()
                    .filter(member -> parameters.containsValue(member.getSpaceId())
                            && parameters.containsValue(member.getUserId()))
                    .findFirst().orElse(null);
        });
        when(memberMapper.selectById(TARGET_MEMBER_ID)).thenReturn(member(TARGET_MEMBER_ID, TARGET_SPACE_ID, 22L, "viewer"));
        when(memberMapper.selectById(OWN_MEMBER_ID)).thenReturn(member(OWN_MEMBER_ID, OWN_SPACE_ID, USER_ID, "admin"));
        SpaceUserServiceImpl memberService = new SpaceUserServiceImpl();
        ReflectionTestUtils.setField(memberService, "baseMapper", memberMapper);
        ReflectionTestUtils.setField(memberService, "entityClass", SpaceUser.class);

        SpaceService spaceService = mock(SpaceService.class);
        targetSpace = space(TARGET_SPACE_ID, 1, 22L);
        when(spaceService.getById(TARGET_SPACE_ID)).thenReturn(targetSpace);
        when(spaceService.getById(OWN_SPACE_ID)).thenReturn(space(OWN_SPACE_ID, 1, USER_ID));
        memberships.put(OWN_SPACE_ID, member(OWN_MEMBER_ID, OWN_SPACE_ID, USER_ID, "admin"));
        UserServiceImpl userService = new UserServiceImpl();
        SpaceUserAuthManager authManager = new SpaceUserAuthManager();
        ReflectionTestUtils.setField(authManager, "spaceUserService", memberService);
        ReflectionTestUtils.setField(authManager, "userService", userService);
        authorization = new StpInterfaceImpl();
        ReflectionTestUtils.setField(authorization, "contextPath", "/api");
        ReflectionTestUtils.setField(authorization, "pictureService", pictureService);
        ReflectionTestUtils.setField(authorization, "spaceService", spaceService);
        ReflectionTestUtils.setField(authorization, "spaceUserService", memberService);
        ReflectionTestUtils.setField(authorization, "userService", userService);
        ReflectionTestUtils.setField(authorization, "spaceUserAuthManager", authManager);

        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        previousContext = SaManager.getSaTokenContext();
        previousStpInterface = SaManager.getStpInterface();
        SaManager.setSaTokenContext(new SaTokenContextForSpring());
        SaManager.setStpInterface(authorization);
        User user = new User();
        user.setId(USER_ID);
        user.setUserRole("user");
        StpKit.SPACE.login(USER_ID);
        StpKit.SPACE.getSession().set(USER_LOGIN_STATE, user);
    }

    @AfterEach
    void tearDown() {
        StpKit.SPACE.logout();
        SaManager.setSaTokenContext(previousContext);
        SaManager.setStpInterface(previousStpInterface);
        RequestContextHolder.resetRequestAttributes();
        memberships.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "application/json; charset=utf-8"})
    void viewerCannotForgeAnAdminRoleInJson(String contentType) {
        asTargetMember("viewer");
        json("/picture/edit", "{\"id\":120,\"spaceUser\":{\"spaceRole\":\"admin\"}}", contentType);
        assertEquals(Collections.singletonList("picture:view"), permissions());
    }

    @Test
    void foreignPictureCannotBeAuthorizedByAnOwnedSpaceId() {
        json("/picture/delete", "{\"id\":120,\"spaceId\":200}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void foreignPublicPictureCannotBeAuthorizedByAnOwnedSpaceId() {
        picture.setSpaceId(null);
        json("/picture/edit", "{\"id\":120,\"spaceId\":200}");
        assertEquals(Collections.singletonList("picture:view"), permissions());
    }

    @Test
    void pictureRequestCannotSwitchToAnOwnedMembershipRow() {
        json("/picture/edit", "{\"id\":120,\"spaceUserId\":888}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void foreignMemberRowCannotBeAuthorizedByAnOwnedSpaceId() {
        asTargetMember("viewer");
        json("/spaceUser/edit", "{\"id\":999,\"spaceId\":200,\"spaceRole\":\"admin\"}");
        assertEquals(Collections.singletonList("picture:view"), permissions());
    }

    @Test
    void memberManagementCannotUseAnUnrelatedOwnedMembershipRow() {
        json("/spaceUser/list", "{\"spaceId\":100,\"spaceUserId\":888}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void addingAMemberCannotUseAnIdThatTheAddRequestDoesNotAccept() {
        json("/spaceUser/add", "{\"spaceId\":100,\"userId\":22,\"spaceRole\":\"admin\",\"id\":888}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void batchEditingCannotUseAnIdThatTheBatchRequestDoesNotAccept() {
        picture.setSpaceId(null);
        picture.setUserId(USER_ID);
        json("/picture/edit/batch", "{\"spaceId\":100,\"pictureIdList\":[121],\"id\":120}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void memberManagementCannotUseAnUnrelatedOwnedPicture() {
        picture.setSpaceId(null);
        picture.setUserId(USER_ID);
        json("/spaceUser/list", "{\"spaceId\":100,\"pictureId\":120}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void memberListingRequiresAnActualSpaceScope() {
        json("/spaceUser/list", "{}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void privatePictureCannotUseTheUploadersIdentityInsteadOfTheSpaceOwner() {
        targetSpace.setSpaceType(0);
        picture.setUserId(USER_ID);
        json("/picture/edit", "{\"id\":120,\"spaceId\":200}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "editor", "admin"})
    void legitimateTeamRolesRemainUnchanged(String role) {
        asTargetMember(role);
        json("/picture/edit", "{\"id\":120}");
        List<String> expected = "viewer".equals(role) ? Collections.singletonList("picture:view")
                : "editor".equals(role) ? Arrays.asList("picture:view", "picture:upload", "picture:edit", "picture:delete")
                : ADMIN_PERMISSIONS;
        assertEquals(expected, permissions());
    }

    @Test
    void legitimateMemberAdminCanEditAMemberInTheirOwnTeam() {
        asTargetMember("admin");
        json("/spaceUser/edit", "{\"id\":999,\"spaceRole\":\"editor\"}");
        assertEquals(ADMIN_PERMISSIONS, permissions());
    }

    @Test
    void publicOwnerCanStillReuploadWithMultipartParameters() {
        picture.setSpaceId(null);
        picture.setUserId(USER_ID);
        request.setRequestURI("/api/picture/upload");
        request.setContentType("multipart/form-data; boundary=test");
        request.setParameter("id", "120");
        assertEquals(ADMIN_PERMISSIONS, permissions());
    }

    @Test
    void newPublicUploadsRemainAllowed() {
        json("/picture/upload/url", "{\"fileUrl\":\"https://example.invalid/image.png\"}");
        assertEquals(ADMIN_PERMISSIONS, permissions());
    }

    @Test
    void outpaintingUsesItsExplicitPictureIdRatherThanASpoofedSpaceId() {
        json("/picture/out_painting/create_task", "{\"pictureId\":120,\"spaceId\":200}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void spacePictureListingCannotUseAnUnrelatedOwnedPicture() {
        picture.setSpaceId(null);
        picture.setUserId(USER_ID);
        json("/picture/list/page/vo", "{\"spaceId\":100,\"pictureId\":120}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @ParameterizedTest
    @MethodSource("pictureMvcAliases")
    void springMappedPictureAliasesCannotUseAnotherSpacesPermissions(String path, boolean parsedPatterns) throws Exception {
        assertMappedAliasDenied(path, "/picture/edit", "{\"id\":120,\"spaceId\":200}", parsedPatterns);
    }

    @ParameterizedTest
    @MethodSource("memberMvcAliases")
    void springMappedMemberAliasesCannotUseAnotherSpacesPermissions(String path, boolean parsedPatterns) throws Exception {
        assertMappedAliasDenied(path, "/spaceUser/edit", "{\"id\":999,\"spaceId\":200,\"spaceRole\":\"admin\"}", parsedPatterns);
    }

    private void assertMappedAliasDenied(String path, String expectedPattern, String body, boolean parsedPatterns) throws Exception {
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(new PermissionProbe(authorization));
        builder.setPatternParser(parsedPatterns ? new PathPatternParser() : null);
        MockMvc mvc = builder.build();
        MvcResult result = mvc.perform(post(URI.create("/api" + path)).contextPath("/api")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        assertNotNull(result.getHandler(), "Spring must actually select the controller for this alias");
        Object matched = result.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern = matched instanceof PathPattern ? ((PathPattern) matched).getPatternString() : (String) matched;
        assertEquals(expectedPattern, pattern.endsWith("/") ? pattern.substring(0, pattern.length() - 1) : pattern);
        assertEquals("/api" + path, result.getRequest().getRequestURI(), "Keep the raw URI distinct from the matched route");
        assertEquals("[]", result.getResponse().getContentAsString());
    }

    static Stream<Arguments> pictureMvcAliases() {
        return Stream.of("/picture/edit;qa", "/picture;qa/edit", "/picture;qa/edit;qa", "/picture/%65dit", "/picture/edit/")
                .flatMap(path -> Stream.of(Arguments.of(path, false), Arguments.of(path, true)));
    }

    static Stream<Arguments> memberMvcAliases() {
        return Stream.of("/spaceUser/edit;qa", "/spaceUser;qa/edit", "/spaceUser;qa/edit;qa", "/spaceUser/%65dit", "/spaceUser/edit/")
                .flatMap(path -> Stream.of(Arguments.of(path, false), Arguments.of(path, true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/picture/edit;qa", "/picture;qa/edit", "/picture;qa/edit;qa", "/picture/%65dit"})
    void fallbackPictureAliasesResolveTheActualPictureOrDeny(String path) {
        json(path, "{\"id\":120,\"spaceId\":200}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/spaceUser/edit;qa", "/spaceUser;qa/edit", "/spaceUser;qa/edit;qa", "/spaceUser/%65dit"})
    void fallbackMemberAliasesResolveTheActualMemberOrDeny(String path) {
        json(path, "{\"id\":999,\"spaceId\":200,\"spaceRole\":\"admin\"}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/picture/unknown", "/picture/%2565dit", "/picture/%ZZdit", "/other/picture/edit",
            "/picture/../spaceUser/edit", "/spaceUser/unknown"})
    void unknownOrAmbiguousFallbackRoutesDoNotAuthorizeAnOwnedSpace(String path) {
        json(path, "{\"id\":120,\"spaceId\":200}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trustedStringAndPathPatternAttributesResolvePictureResources(boolean parsedPattern) {
        json("/picture;qa/%65dit", "{\"id\":120,\"spaceId\":200}");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                parsedPattern ? new PathPatternParser().parse("/picture/edit") : "/picture/edit");
        assertEquals(Collections.emptyList(), permissions());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trustedStringAndPathPatternAttributesResolveMemberResources(boolean parsedPattern) {
        json("/spaceUser;qa/%65dit", "{\"id\":999,\"spaceId\":200,\"spaceRole\":\"admin\"}");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                parsedPattern ? new PathPatternParser().parse("/spaceUser/edit") : "/spaceUser/edit");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void unknownTrustedMappingDoesNotFallBackToRawSpaceAuthorization() {
        json("/picture/upload/url", "{\"spaceId\":200}");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/picture/{action}");
        assertEquals(Collections.emptyList(), permissions());
    }

    @Test
    void unsupportedMappingAttributeDoesNotFallBackToAnOwnedSpace() {
        json("/picture/unknown", "{\"id\":120,\"spaceId\":200}");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, new Object());
        assertEquals(Collections.emptyList(), permissions());
    }

    @ParameterizedTest
    @MethodSource("trustedHappyPaths")
    void trustedRoutesPreserveCanonicalUploadAndRoleOperations(String path, String body, String role, List<String> expected) {
        asTargetMember(role);
        json("/unresolved/raw/uri", body);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path);
        assertEquals(expected, permissions());
    }

    static Stream<Arguments> trustedHappyPaths() {
        List<String> editor = Arrays.asList("picture:view", "picture:upload", "picture:edit", "picture:delete");
        return Stream.of(
                Arguments.of("/picture/upload", "{}", "viewer", ADMIN_PERMISSIONS),
                Arguments.of("/picture/upload/url", "{\"fileUrl\":\"https://example.invalid/image.png\"}", "viewer", ADMIN_PERMISSIONS),
                Arguments.of("/picture/edit", "{\"id\":120}", "editor", editor),
                Arguments.of("/picture/edit/batch", "{\"spaceId\":100,\"pictureIdList\":[120]}", "editor", editor),
                Arguments.of("/picture/out_painting/create_task", "{\"pictureId\":120}", "editor", editor),
                Arguments.of("/spaceUser/edit", "{\"id\":999,\"spaceRole\":\"editor\"}", "admin", ADMIN_PERMISSIONS),
                Arguments.of("/spaceUser/add", "{\"spaceId\":100,\"userId\":22,\"spaceRole\":\"viewer\"}", "admin", ADMIN_PERMISSIONS));
    }

    @RestController
    static class PermissionProbe {
        private final StpInterfaceImpl authorization;

        PermissionProbe(StpInterfaceImpl authorization) {
            this.authorization = authorization;
        }

        @PostMapping({"/picture/edit", "/spaceUser/edit"})
        public List<String> permissions() {
            return authorization.getPermissionList(USER_ID, StpKit.SPACE_TYPE);
        }
    }

    private List<String> permissions() {
        return authorization.getPermissionList(USER_ID, StpKit.SPACE_TYPE);
    }

    private void json(String route, String body) {
        json(route, body, "application/json");
    }

    private void json(String route, String body, String contentType) {
        request.setMethod("POST");
        request.setRequestURI("/api" + route);
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
    }

    private void asTargetMember(String role) {
        memberships.put(TARGET_SPACE_ID, member(777L, TARGET_SPACE_ID, USER_ID, role));
    }

    private Space space(long id, int type, long owner) {
        Space space = new Space();
        space.setId(id);
        space.setSpaceType(type);
        space.setUserId(owner);
        return space;
    }

    private SpaceUser member(long id, long spaceId, long userId, String role) {
        SpaceUser member = new SpaceUser();
        member.setId(id);
        member.setSpaceId(spaceId);
        member.setUserId(userId);
        member.setSpaceRole(role);
        return member;
    }
}
