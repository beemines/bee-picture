package com.bee.beepicturebackend.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bee.beepicturebackend.exception.BusinessException;
import com.bee.beepicturebackend.exception.ErrorCode;
import com.bee.beepicturebackend.mapper.UserMapper;
import com.bee.beepicturebackend.model.dto.user.UserAddRequest;
import com.bee.beepicturebackend.model.dto.user.UserUpdateRequest;
import com.bee.beepicturebackend.model.entity.User;
import com.bee.beepicturebackend.service.impl.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserManagementValidationTest {

    private UserController controller;
    private UserServiceImpl service;
    private UserMapper mapper;
    private final List<User> saved = new ArrayList<>();
    private final List<User> updated = new ArrayList<>();
    private final Set<String> existingAccounts = new HashSet<>();

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), User.class);
        mapper = mock(UserMapper.class);
        when(mapper.selectCount(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().values().stream().anyMatch(existingAccounts::contains) ? 1L : 0L;
        });
        when(mapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(120L);
            saved.add(user);
            return 1;
        });
        when(mapper.updateById(any(User.class))).thenAnswer(invocation -> {
            updated.add(invocation.getArgument(0));
            return 1;
        });
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "entityClass", User.class);
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", service);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "a", "abc"})
    void addRejectsBlankAndShortAccountsBeforePersistence(String account) {
        assertInvalidAccount(account);
    }

    @Test
    void addRejectsAccountsLongerThanTheDatabaseColumn() {
        assertInvalidAccount("a".repeat(257));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "root", "ADMIN"})
    void addRejectsUnsupportedRolesBeforePersistence(String role) {
        UserAddRequest request = addRequest("valid-account");
        request.setUserRole(role);
        BusinessException error = assertThrows(BusinessException.class, () -> controller.addUser(request));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("角色"));
        assertTrue(saved.isEmpty());
    }

    @Test
    void addRejectsAnExistingAccountWithAnActionableBusinessError() {
        existingAccounts.add("already-used");
        assertDuplicateAccount(() -> controller.addUser(addRequest("already-used")));
        assertTrue(saved.isEmpty());
    }

    @Test
    void addConvertsAConcurrentUniqueConstraintFailureToTheSameBusinessError() {
        when(mapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("uk_userAccount"));
        assertDuplicateAccount(() -> controller.addUser(addRequest("race-account")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void addPreservesSupportedRolesAndTheExistingInitialPassword(String role) {
        UserAddRequest request = addRequest("账+@!");
        request.setUserRole(role);
        assertEquals(120L, controller.addUser(request).getData());
        assertEquals(1, saved.size());
        assertEquals("账+@!", saved.get(0).getUserAccount());
        assertEquals(role, saved.get(0).getUserRole());
        assertEquals(service.getEncryptPassword("12345678"), saved.get(0).getUserPassword());
    }

    @Test
    void addAllowsTheColumnBoundaryAndDefaultsAnOmittedRoleToUser() {
        controller.addUser(addRequest("a".repeat(256)));
        assertEquals(256, saved.get(0).getUserAccount().length());
        assertEquals("user", saved.get(0).getUserRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "root", "ADMIN"})
    void updateRejectsUnsupportedRoleChangesBeforePersistence(String role) {
        UserUpdateRequest request = updateRequest();
        request.setUserRole(role);
        BusinessException error = assertThrows(BusinessException.class, () -> controller.updateUser(request));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("角色"));
        assertTrue(updated.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "admin"})
    void updateAllowsSupportedRoleChanges(String role) {
        UserUpdateRequest request = updateRequest();
        request.setUserRole(role);
        assertTrue(controller.updateUser(request).getData());
        assertEquals(role, updated.get(0).getUserRole());
    }

    @Test
    void updateAllowsProfileEditsWithoutSupplyingARole() {
        UserUpdateRequest request = updateRequest();
        request.setUserName("new name");
        assertTrue(controller.updateUser(request).getData());
        assertEquals("new name", updated.get(0).getUserName());
        assertNull(updated.get(0).getUserRole());
    }

    private void assertInvalidAccount(String account) {
        BusinessException error = assertThrows(BusinessException.class, () -> controller.addUser(addRequest(account)));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("账号"));
        assertTrue(saved.isEmpty());
    }

    private void assertDuplicateAccount(org.junit.jupiter.api.function.Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("账号"));
        assertTrue(error.getMessage().contains("重复") || error.getMessage().contains("存在"));
    }

    private UserAddRequest addRequest(String account) {
        UserAddRequest request = new UserAddRequest();
        request.setUserAccount(account);
        return request;
    }

    private UserUpdateRequest updateRequest() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setId(120L);
        return request;
    }
}
