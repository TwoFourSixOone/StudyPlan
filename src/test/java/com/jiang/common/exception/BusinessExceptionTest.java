package com.jiang.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BusinessException 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
class BusinessExceptionTest {

    @Test
    @DisplayName("测试使用错误码枚举构造异常")
    void testConstructorWithErrorCodes() {
        BusinessException exception = new BusinessException(ErrorCodes.STUDENT_NOT_FOUND);
        
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getCode(), exception.getCode());
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getMessage(), exception.getMessage());
    }

    @Test
    @DisplayName("测试使用自定义错误码和信息构造异常")
    void testConstructorWithCustomCodeAndMessage() {
        BusinessException exception = new BusinessException(1099, "自定义错误信息");
        
        assertEquals(1099, exception.getCode());
        assertEquals("自定义错误信息", exception.getMessage());
    }

    @Test
    @DisplayName("测试使用错误码枚举和自定义信息构造异常")
    void testConstructorWithErrorCodesAndCustomMessage() {
        BusinessException exception = new BusinessException(
            ErrorCodes.STUDENT_NOT_FOUND, "学生 ID 12345 不存在");
        
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getCode(), exception.getCode());
        assertEquals("学生 ID 12345 不存在", exception.getMessage());
    }

    @Test
    @DisplayName("测试异常继承 RuntimeException")
    void testExceptionInheritance() {
        BusinessException exception = new BusinessException(ErrorCodes.BAD_REQUEST);
        
        assertTrue(exception instanceof RuntimeException);
    }
}
