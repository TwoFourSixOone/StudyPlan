package com.jiang.service;

import com.jiang.common.handler.TenantHandler;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.mapper.StudentMapper;
import com.jiang.model.entity.Student;
import com.jiang.security.JwtUtil;
import com.jiang.vo.LoginResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StudentLoginTxService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@ExtendWith(MockitoExtension.class)
class StudentLoginTxServiceTest {

    @Mock
    private StudentMapper studentMapper;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private StudentLoginTxService studentLoginTxService;

    private static final String TEST_OPENID = "test_openid";

    @Test
    void should_createNewStudent_when_firstLogin() {
        when(studentMapper.selectOne(any())).thenReturn(null);
        when(studentMapper.insert(any(Student.class))).thenAnswer(invocation -> {
            Student s = invocation.getArgument(0);
            s.setStudentId(1L);
            s.setTenantId(TenantHandler.DEFAULT_TENANT_ID); // 模拟 AutoFillHandler
            return 1;
        });
        when(jwtUtil.generateToken(eq(TEST_OPENID), eq(1L), eq(TenantHandler.DEFAULT_TENANT_ID))).thenReturn("jwt_token");

        LoginResponse result = studentLoginTxService.transactionalLogin(TEST_OPENID);

        assertNotNull(result);
        assertEquals("jwt_token", result.getToken());
        verify(studentMapper).insert(any(Student.class));
    }

    @Test
    void should_returnExistingStudent_when_notFirstLogin() {
        Student existing = new Student();
        existing.setStudentId(1L);
        existing.setOpenid(TEST_OPENID);
        existing.setNickname("Test");
        existing.setTenantId(TenantHandler.DEFAULT_TENANT_ID);
        when(studentMapper.selectOne(any())).thenReturn(existing);
        when(jwtUtil.generateToken(eq(TEST_OPENID), eq(1L), eq(TenantHandler.DEFAULT_TENANT_ID))).thenReturn("jwt_token");

        LoginResponse result = studentLoginTxService.transactionalLogin(TEST_OPENID);

        assertNotNull(result);
        assertEquals("jwt_token", result.getToken());
        verify(studentMapper, never()).insert(any());
    }

    @Test
    void should_fallbackToSelect_when_duplicateKeyOnInsert() {
        when(studentMapper.selectOne(any()))
                .thenReturn(null) // 第一次查询返回 null（首次登录）
                .thenReturn(createStudent(1L, TEST_OPENID)); // 冲突后回查
        when(studentMapper.insert(any(Student.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(jwtUtil.generateToken(eq(TEST_OPENID), eq(1L), eq(TenantHandler.DEFAULT_TENANT_ID))).thenReturn("jwt_token");

        LoginResponse result = studentLoginTxService.transactionalLogin(TEST_OPENID);

        assertNotNull(result);
        assertEquals("jwt_token", result.getToken());
        verify(studentMapper, times(1)).insert(any());
    }

    @Test
    void should_throwInternalError_when_fallbackSelectReturnsNull() {
        when(studentMapper.selectOne(any())).thenReturn(null);
        when(studentMapper.insert(any(Student.class))).thenThrow(new DuplicateKeyException("duplicate"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> studentLoginTxService.transactionalLogin(TEST_OPENID));
        assertEquals(ErrorCodes.INTERNAL_ERROR.getCode(), ex.getCode());
    }

    private Student createStudent(Long id, String openid) {
        Student s = new Student();
        s.setStudentId(id);
        s.setOpenid(openid);
        s.setNickname("Test");
        s.setTenantId(TenantHandler.DEFAULT_TENANT_ID);
        return s;
    }
}
