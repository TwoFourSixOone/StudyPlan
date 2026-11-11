package com.jiang.common.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 试卷类型枚举映射（design §2.4 / S13）
 *
 * <p>固定 5 个取值：{@code midterm/final/monthly/mock/other}，默认 {@code midterm}，
 * 非法值 → {@code 1191}（由调用方抛业务异常）。前端 picker 与后端校验同源。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public final class ExamPaperTypeUtil {

    /** 默认试卷类型 */
    public static final String DEFAULT_TYPE = "midterm";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("midterm", "期中考试");
        LABELS.put("final", "期末考试");
        LABELS.put("monthly", "月考");
        LABELS.put("mock", "模拟考试");
        LABELS.put("other", "其他");
    }

    private ExamPaperTypeUtil() {
    }

    /**
     * 是否合法取值（大小写敏感，调用方先 trim）
     */
    public static boolean isValid(String code) {
        return code != null && LABELS.containsKey(code.trim());
    }

    /**
     * 归一化：空白 → 默认 midterm；非空白原样返回（合法性由 {@link #isValid} 判定）
     */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT_TYPE;
        }
        return code.trim();
    }

    /**
     * code → 中文名（未知 code 回退原值，不抛异常）
     */
    public static String displayName(String code) {
        if (code == null) {
            return LABELS.get(DEFAULT_TYPE);
        }
        return LABELS.getOrDefault(code.trim(), code);
    }

    /**
     * 合法取值列表（错误文案用）
     */
    public static String allowedValues() {
        return String.join("/", LABELS.keySet());
    }
}
