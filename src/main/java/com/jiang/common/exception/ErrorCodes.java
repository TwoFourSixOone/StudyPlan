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
    HOMEWORK_ALREADY_EXISTS(1052, "当日作业已存在，请勿重复生成"),
    STUDENT_IDENTITY_MISMATCH(1053, "学生身份不匹配"),
    HOMEWORK_NOT_SUBMITTED(1054, "作业未提交，无法批改"),
    HOMEWORK_ALREADY_GRADED(1055, "作业已批改，请勿重复批改"),
    HOMEWORK_CONCURRENT_CONFLICT(1056, "作业状态更新冲突，请重试"),
    LEARNING_GOAL_NOT_FOUND(1057, "无有效学习目标，请先设定学习目标"),
    CONTENT_PARSE_ERROR(1058, "作业内容 JSON 解析失败"),
    CONTENT_SERIALIZE_ERROR(1059, "作业内容 JSON 序列化失败"),

    // 掌握度相关（1060-1069）
    MASTERY_STATE_NOT_FOUND(1060, "掌握度状态不存在"),
    MASTERY_CONCURRENT_CONFLICT(1061, "掌握度更新冲突，请重试"),

    // 数据导入相关（1070-1079）
    IMPORT_FILE_NOT_FOUND(1070, "导入文件不存在"),
    IMPORT_FORMAT_ERROR(1071, "导入文件格式错误"),
    IMPORT_VALIDATION_FAILED(1072, "导入数据校验失败"),

    // Agent 相关（1080-1089）
    AGENT_CALL_FAILED(1080, "Agent 调用失败"),
    AGENT_DEGRADED(1081, "Agent 已降级处理"),
    AGENT_OUTPUT_INVALID(1082, "Agent 输出校验失败"),

    // 科目相关（1090-1099）
    SUBJECT_NOT_FOUND(1090, "科目不存在"),
    SUBJECT_NO_ACTIVE_KP(1091, "科目下无已启用知识点"),

    // 错题相关（1100-1109）
    ERROR_QUESTION_NOT_FOUND(1100, "错题不存在"),
    ERROR_QUESTION_COMPLETED(1101, "错题已完成，无法推进"),
    ERROR_QUESTION_INVALID_STATUS(1102, "错题状态无效"),
    ERROR_QUESTION_CONCURRENT_MODIFIED(1103, "错题并发修改冲突，请重试"),

    // 举一反三相关（1110-1119）
    SIMILAR_QUESTION_GEN_FAILED(1110, "举一反三新题生成失败"),
    SIMILAR_QUESTION_CHECK_FAILED(1111, "举一反三新题校验失败"),
    SIMILAR_QUESTION_TOO_SIMILAR(1112, "举一反三新题与原题相似度过高"),
    SIMILAR_AGENT_OUTPUT_INVALID(1113, "检查 Agent 输出格式异常"),

    // 专项评测相关（1120-1129）
    ASSESSMENT_NOT_FOUND(1120, "评测不存在"),
    ASSESSMENT_INVALID_STATUS(1121, "评测状态非法"),
    ASSESSMENT_ALREADY_GRADED(1122, "评测已批改，请勿重复提交"),
    ASSESSMENT_CONCURRENT_MODIFIED(1123, "评测状态更新冲突，请重试"),

    // 试卷相关（1130-1139）
    EXAM_PAPER_NOT_FOUND(1130, "试卷不存在"),
    EXAM_PAPER_DUPLICATE(1131, "同一科目同一天只能上传一份试卷"),
    EXAM_PAPER_ALREADY_GRADED(1132, "试卷已批改，请勿重复提交"),
    EXAM_PAPER_NOT_READY_FOR_GRADE(1133, "试卷尚未完成解析，无法批改"),
    EXAM_QUESTION_NOT_FOUND(1134, "试卷题目不存在"),
    EXAM_TASK_NOT_FOUND(1135, "解析任务不存在"),
    EXAM_RESULT_NOT_FOUND(1136, "分析结果不存在"),
    OCR_FAILED(1137, "OCR 识别失败"),
    FILE_UPLOAD_FAILED(1138, "文件上传失败"),
    FILE_TYPE_INVALID(1139, "文件类型无效，仅支持 jpg/jpeg/png"),

    // 计划调整相关（1140-1149）
    PLAN_ADJUST_NOT_FOUND(1140, "计划调整记录不存在"),
    PLAN_DIFF_NOT_FOUND_OR_INVALID(1141, "diff 不存在或状态无效"),
    PLAN_DIFF_ALREADY_CONFIRMED(1142, "diff 已确认，请勿重复操作"),
    PLAN_DIFF_EXPIRED(1143, "diff 已过期"),
    SIGNAL_DETECT_FAILED(1144, "信号检测失败"),
    ADJUST_AGENT_FAILED(1145, "调整 Agent 调用失败"),
    PLAN_DIFF_ALREADY_REJECTED(1146, "diff 已拒绝"),
    NO_SIGNAL_DETECTED(1147, "暂无需调整"),
    PLAN_ADJUST_CONFLICT(1148, "已有进行中的调整任务"),
    PLAN_DATE_IN_PAST(1149, "不可修改过去日期的计划"),

    // 学习记录相关（1150-1159）
    RECORD_NOT_FOUND(1150, "学习记录不存在"),
    INVALID_RECORD_TYPE(1151, "无效的记录类型，合法值：homework/assessment/exam"),
    RECORD_ALREADY_EXISTS(1152, "学习记录已存在，不可重复创建"),

    // 知识点治理相关（1160-1169）
    KP_NOT_PENDING_REVIEW(1161, "知识点不在待校对状态"),
    KP_IN_USE(1162, "知识点被活跃学习计划引用，无法停用"),
    KP_INVALID_STATUS(1163, "无效的状态值，合法值：active/disabled"),

    // 管理后端列表相关（1170-1179）
    IMPORT_LOG_NOT_FOUND(1170, "导入日志不存在"),
    IMPORT_ERROR_LOG_NOT_FOUND(1171, "导入错误记录不存在");

    private final Integer code;
    private final String message;

    ErrorCodes(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
