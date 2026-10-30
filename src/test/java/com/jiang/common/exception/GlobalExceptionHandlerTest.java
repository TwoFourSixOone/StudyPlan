package com.jiang.common.exception;

import com.jiang.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GlobalExceptionHandler 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @Mock
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new GlobalExceptionHandler();
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    @DisplayName("测试处理业务异常")
    void testHandleBusinessException() {
        BusinessException exception = new BusinessException(ErrorCodes.STUDENT_NOT_FOUND);
        
        Result<Void> result = handler.handleBusinessException(exception, request);
        
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getCode(), result.getCode());
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getMessage(), result.getMessage());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("测试处理自定义业务异常")
    void testHandleCustomBusinessException() {
        BusinessException exception = new BusinessException(1099, "自定义错误");
        
        Result<Void> result = handler.handleBusinessException(exception, request);
        
        assertEquals(1099, result.getCode());
        assertEquals("自定义错误", result.getMessage());
    }

    @Test
    @DisplayName("测试处理参数校验异常")
    void testHandleValidationException() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError1 = new FieldError("object", "field1", "字段1不能为空");
        FieldError fieldError2 = new FieldError("object", "field2", "字段2格式错误");
        
        when(exception.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(Arrays.asList(fieldError1, fieldError2));
        
        Result<Void> result = handler.handleValidationException(exception);
        
        assertEquals(ErrorCodes.BAD_REQUEST.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("字段1不能为空"));
        assertTrue(result.getMessage().contains("字段2格式错误"));
    }

    @Test
    @DisplayName("测试处理请求方法不支持异常")
    void testHandleMethodNotSupported() {
        HttpRequestMethodNotSupportedException exception = 
            new HttpRequestMethodNotSupportedException("POST");
        
        Result<Void> result = handler.handleMethodNotSupported(exception);
        
        assertEquals(ErrorCodes.METHOD_NOT_ALLOWED.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("POST"));
    }

    @Test
    @DisplayName("测试处理资源不存在异常")
    void testHandleNoResourceFound() {
        NoResourceFoundException exception = new NoResourceFoundException(
            org.springframework.http.HttpMethod.GET, "test-resource");
        
        Result<Void> result = handler.handleNoResourceFound(exception);
        
        assertEquals(ErrorCodes.NOT_FOUND.getCode(), result.getCode());
    }

    @Test
    @DisplayName("测试处理未知异常")
    void testHandleException() {
        RuntimeException exception = new RuntimeException("未知错误");
        
        Result<Void> result = handler.handleException(exception, request);
        
        assertEquals(ErrorCodes.INTERNAL_ERROR.getCode(), result.getCode());
        assertEquals("系统异常，请稍后重试", result.getMessage());
    }
}
