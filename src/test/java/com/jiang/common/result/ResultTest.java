package com.jiang.common.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.ErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Result 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
class ResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("测试成功响应（无数据）")
    void testSuccessWithoutData() {
        Result<Void> result = Result.success();
        
        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertNull(result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("测试成功响应（带数据）")
    void testSuccessWithData() {
        String testData = "Hello, StudyMate!";
        Result<String> result = Result.success(testData);
        
        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertEquals(testData, result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("测试错误响应（自定义错误码）")
    void testErrorWithCustomCode() {
        Result<Void> result = Result.error(1000, "学生不存在");
        
        assertEquals(1000, result.getCode());
        assertEquals("学生不存在", result.getMessage());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("测试错误响应（使用错误码枚举）")
    void testErrorWithErrorCodeEnum() {
        Result<Void> result = Result.error(ErrorCodes.STUDENT_NOT_FOUND);
        
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getCode(), result.getCode());
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getMessage(), result.getMessage());
        assertNull(result.getData());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("测试 JSON 序列化格式")
    void testJsonSerialization() throws Exception {
        Result<String> result = Result.success("test data");
        String json = objectMapper.writeValueAsString(result);
        
        assertTrue(json.contains("\"code\":200"));
        assertTrue(json.contains("\"message\":\"操作成功\""));
        assertTrue(json.contains("\"data\":\"test data\""));
    }
}
