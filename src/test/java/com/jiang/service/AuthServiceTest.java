package com.jiang.service;

import com.jiang.client.WechatApiClient;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.mapper.StudentMapper;
import com.jiang.model.entity.Student;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.AuthServiceImpl;
import com.jiang.vo.LoginResponse;
import com.jiang.vo.StudentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private StudentLoginTxService txService;

    @Mock
    private StudentMapper studentMapper;

    @Mock
    private WechatApiClient wechatApiClient;

    @InjectMocks
    private AuthServiceImpl authService;

    private static final String TEST_CODE = "test_code";
    private static final String TEST_OPENID = "test_openid";

    @AfterEach
    void tearDown() {
        StudentContext.clear();
    }

    @Test
    void should_delegateToTxService_when_loginSuccess() throws Exception {
        when(wechatApiClient.fetchOpenid(TEST_CODE)).thenReturn(TEST_OPENID);

        LoginResponse expected = new LoginResponse();
        expected.setToken("jwt_token");
        when(txService.transactionalLogin(TEST_OPENID)).thenReturn(expected);

        LoginResponse result = authService.login(TEST_CODE);

        assertNotNull(result);
        assertEquals("jwt_token", result.getToken());
        verify(wechatApiClient).fetchOpenid(TEST_CODE);
        verify(txService).transactionalLogin(TEST_OPENID);
    }

    @Test
    void should_returnStudentVO_when_currentStudentExists() {
        StudentContext.setStudentId(1L);
        Student student = new Student();
        student.setStudentId(1L);
        student.setNickname("Test");
        when(studentMapper.selectById(1L)).thenReturn(student);

        StudentVO result = authService.getCurrentStudent();

        assertNotNull(result);
        assertEquals(1L, result.getStudentId());
        assertEquals("Test", result.getNickname());
    }

    @Test
    void should_throwUnauthorized_when_currentStudentNotFound() {
        StudentContext.setStudentId(999L);
        when(studentMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.getCurrentStudent());
        assertEquals(ErrorCodes.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void should_propagateBusinessException_when_wechatApiFails() throws Exception {
        when(wechatApiClient.fetchOpenid(TEST_CODE))
                .thenThrow(new BusinessException(ErrorCodes.OPENID_INVALID, "微信登录失败"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(TEST_CODE));
        assertEquals(ErrorCodes.OPENID_INVALID.getCode(), ex.getCode());
        verify(txService, never()).transactionalLogin(anyString());
    }
}
