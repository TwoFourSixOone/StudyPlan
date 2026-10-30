package com.jiang.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ErrorCodes 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
class ErrorCodesTest {

    @Test
    @DisplayName("测试成功错误码")
    void testSuccessCode() {
        assertEquals(200, ErrorCodes.SUCCESS.getCode());
        assertEquals("操作成功", ErrorCodes.SUCCESS.getMessage());
    }

    @Test
    @DisplayName("测试客户端错误码范围（400-499）")
    void testClientErrorCodes() {
        assertTrue(ErrorCodes.BAD_REQUEST.getCode() >= 400 && ErrorCodes.BAD_REQUEST.getCode() < 500);
        assertTrue(ErrorCodes.UNAUTHORIZED.getCode() >= 400 && ErrorCodes.UNAUTHORIZED.getCode() < 500);
        assertTrue(ErrorCodes.FORBIDDEN.getCode() >= 400 && ErrorCodes.FORBIDDEN.getCode() < 500);
        assertTrue(ErrorCodes.NOT_FOUND.getCode() >= 400 && ErrorCodes.NOT_FOUND.getCode() < 500);
    }

    @Test
    @DisplayName("测试服务端错误码范围（500-599）")
    void testServerErrorCodes() {
        assertTrue(ErrorCodes.INTERNAL_ERROR.getCode() >= 500 && ErrorCodes.INTERNAL_ERROR.getCode() < 600);
    }

    @Test
    @DisplayName("测试业务错误码范围（1000+）")
    void testBusinessErrorCodes() {
        assertTrue(ErrorCodes.STUDENT_NOT_FOUND.getCode() >= 1000);
        assertTrue(ErrorCodes.KNOWLEDGE_POINT_NOT_FOUND.getCode() >= 1000);
        assertTrue(ErrorCodes.AGENT_CALL_FAILED.getCode() >= 1000);
    }

    @ParameterizedTest
    @EnumSource(ErrorCodes.class)
    @DisplayName("测试所有错误码都有 code 和 message")
    void testAllErrorCodesHaveCodeAndMessage(ErrorCodes errorCode) {
        assertNotNull(errorCode.getCode(), "错误码 code 不能为空");
        assertNotNull(errorCode.getMessage(), "错误码 message 不能为空");
        assertFalse(errorCode.getMessage().isEmpty(), "错误码 message 不能为空字符串");
    }

    @Test
    @DisplayName("测试错误码唯一性")
    void testErrorCodeUniqueness() {
        ErrorCodes[] allCodes = ErrorCodes.values();
        long uniqueCodeCount = java.util.Arrays.stream(allCodes)
            .map(ErrorCodes::getCode)
            .distinct()
            .count();
        
        assertEquals(allCodes.length, uniqueCodeCount, "所有错误码应该是唯一的");
    }
}
