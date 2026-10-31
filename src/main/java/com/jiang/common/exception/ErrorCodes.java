package com.jiang.common.exception;

import lombok.Getter;

/**
 * 错误码枚举
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Getter
public enum ErrorCodes {

    // 成功
    SUCCESS(200, "操作成功"),

    // 客户端错误（400-499）
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    CONFLICT(409, "资源冲突"),

    // 服务端错误（500-599）
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),

    // 业务错误（1000-1999）
    // 学生相关（1000-1009）
    STUDENT_NOT_FOUND(1000, "学生不存在"),
    STUDENT_ALREADY_EXISTS(1001, "学生已存在"),
    OPENID_INVALID(1002, "openid 无效"),

    // 知识点相关（1010-1019）
    KNOWLEDGE_POINT_NOT_FOUND(1010, "知识点不存在"),
    KNOWLEDGE_POINT_DISABLED(1011, "知识点已停用"),
    KNOWLEDGE_POINT_PENDING_REVIEW(1012, "知识点待校对"),

    // 题目相关（1020-1029）
    QUESTION_NOT_FOUND(1020, "题目不存在"),

    // 目标相关（1030-1039）
    GOAL_NOT_FOUND(1030, "学习目标不存在"),
    GOAL_EXPIRED(1031, "学习目标已过期"),
    GOAL_ALREADY_ACTIVE(1032, "该科目已有进行中的学习目标"),
    GOAL_NOT_MODIFIABLE(1033, "目标不可修改（已完成或已过期）"),
    DATE_INVALID(1034, "日期不合法（结束日期须晚于开始日期，开始日期不得早于今天，结束日期不得缩短）"),

    // 计划相关（1040-1049）
    PLAN_NOT_FOUND(1040, "学习计划不存在"),
    PLAN_GENERATION_FAILED(1041, "计划生成失败"),

    // 作业相关（1050-1059）
    HOMEWORK_NOT_FOUND(1050, "每日作业不存在"),
    HOMEWORK_ALREADY_SUBMITTED(1051, "作业已提交"),

    // 掌握度相关（1060-1069）
    MASTERY_STATE_NOT_FOUND(1060, "掌握度状态不存在"),

    // 数据导入相关（1070-1079）
    IMPORT_FILE_NOT_FOUND(1070, "导入文件不存在"),
    IMPORT_FORMAT_ERROR(1071, "导入文件格式错误"),
    IMPORT_VALIDATION_FAILED(1072, "导入数据校验失败"),

    // Agent 相关（1080-1089）
    AGENT_CALL_FAILED(1080, "Agent 调用失败"),
    AGENT_DEGRADED(1081, "Agent 已降级处理"),

    // 科目相关（1090-1099）
    SUBJECT_NOT_FOUND(1090, "科目不存在"),
    SUBJECT_NO_ACTIVE_KP(1091, "科目下无已启用知识点");

    private final Integer code;
    private final String message;

    ErrorCodes(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
