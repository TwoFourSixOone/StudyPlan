package com.jiang.common.result;

import com.jiang.common.exception.ErrorCodes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应结构
 *
 * @param <T> 数据类型
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务状态码（200=成功，非200=错误）
     */
    private Integer code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 业务数据
     */
    private T data;

    /**
     * 成功响应（无数据）
     */
    public static <T> Result<T> success() {
        return new Result<>(ErrorCodes.SUCCESS.getCode(), ErrorCodes.SUCCESS.getMessage(), null);
    }

    /**
     * 成功响应（带数据）
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCodes.SUCCESS.getCode(), ErrorCodes.SUCCESS.getMessage(), data);
    }

    /**
     * 错误响应
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 错误响应（使用错误码枚举）
     */
    public static <T> Result<T> error(ErrorCodes errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return ErrorCodes.SUCCESS.getCode().equals(this.code);
    }
}
