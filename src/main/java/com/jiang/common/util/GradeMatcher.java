package com.jiang.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 年级匹配工具（产品规则 #11）
 *
 * <p>纯静态工具类，无 Spring 依赖。负责三件事：</p>
 * <ol>
 *   <li>把 {@code student.grade}（VARCHAR）归一化为 1–12 的整数</li>
 *   <li>校验 {@code knowledge_point.grade_range} 的语法与取值</li>
 *   <li>判断学生年级是否匹配知识点范围 / 题目年级</li>
 * </ol>
 *
 * <p><b>降级约定</b>：学生年级为空、范围为空或非法时一律返回 {@code true}（不过滤），
 * 宁可多推内容也不能把内容池筛成空。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Slf4j
public final class GradeMatcher {

    /** 年级下限（小学一年级） */
    private static final int MIN_GRADE = 1;
    /** 年级上限（高三） */
    private static final int MAX_GRADE = 12;

    /** 纯数字："3" */
    private static final Pattern DIGITS = Pattern.compile("^\\d{1,2}$");
    /** 数字 + 年级后缀："3年级" / "3级" */
    private static final Pattern DIGITS_WITH_SUFFIX = Pattern.compile("^(\\d{1,2})年?级$");
    /** 中文数字 + 年级："三年级" */
    private static final Pattern CN_GRADE = Pattern.compile("^([一二三四五六])年级$");

    /** 初中中文别名 */
    private static final Map<String, Integer> JUNIOR_ALIAS = Map.of("初一", 7, "初二", 8, "初三", 9);
    /** 高中中文别名 */
    private static final Map<String, Integer> SENIOR_ALIAS = Map.of("高一", 10, "高二", 11, "高三", 12);
    /** 中文数字（小学） */
    private static final Map<String, Integer> CN_DIGITS =
            Map.of("一", 1, "二", 2, "三", 3, "四", 4, "五", 5, "六", 6);

    private GradeMatcher() {
        // 工具类禁止实例化
    }

    // ==================== 学生年级归一化 ====================

    /**
     * 学生年级字符串 → 1–12；无法识别返回 {@code null}（调用方视为不过滤）
     *
     * <p>支持："9"、" 9 "、"三年级"、"3年级"、"初一"、"高一"；
     * 学段级（"小学"/"初中"/"高中"）无法定位单一年级，同样返回 {@code null}。</p>
     *
     * @param raw {@code student.grade} 原始值
     * @return 归一化年级，或 null
     */
    public static Integer normalizeStudentGrade(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (DIGITS.matcher(s).matches()) {
            return parseGrade(s);
        }
        Matcher m = DIGITS_WITH_SUFFIX.matcher(s);
        if (m.matches()) {
            return parseGrade(m.group(1));
        }
        m = CN_GRADE.matcher(s);
        if (m.matches()) {
            return CN_DIGITS.get(m.group(1));
        }
        if (JUNIOR_ALIAS.containsKey(s)) {
            return JUNIOR_ALIAS.get(s);
        }
        if (SENIOR_ALIAS.containsKey(s)) {
            return SENIOR_ALIAS.get(s);
        }
        if ("小学".equals(s) || "初中".equals(s) || "高中".equals(s)) {
            // 学段级信息不足以定位单一年级，按"不过滤"处理
            return null;
        }
        log.warn("无法识别的学生年级，按不过滤处理: raw={}", raw);
        return null;
    }

    // ==================== 范围校验 ====================

    /**
     * 校验年级范围是否合法（语法 + 取值）
     *
     * <p>合法形态：{@code "3"}、{@code "7-9"}、{@code "1,2,3"} 及其组合。
     * {@code null}/空白视为合法（字段可选）。</p>
     *
     * <p><b>注意</b>：不能只做正则匹配——{@code "0"}、{@code "13"}、{@code "9-7"}
     * 都能通过正则，但入库后会导致该知识点对所有学生都不匹配（内容黑洞）。</p>
     *
     * @param gradeRange 年级范围
     * @return 是否合法
     */
    public static boolean isValidRange(String gradeRange) {
        if (gradeRange == null || gradeRange.isBlank()) {
            return true;
        }
        String[] tokens = gradeRange.split(",", -1);
        if (tokens.length == 0) {
            return false;
        }
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) {
                return false;
            }
            if (t.contains("-")) {
                String[] parts = t.split("-", -1);
                if (parts.length != 2) {
                    return false;
                }
                Integer lo = parseGrade(parts[0].trim());
                Integer hi = parseGrade(parts[1].trim());
                if (lo == null || hi == null || lo > hi) {
                    return false;
                }
            } else if (parseGrade(t) == null) {
                return false;
            }
        }
        return true;
    }

    // ==================== 匹配判定 ====================

    /**
     * 学生年级是否落在知识点年级范围内
     *
     * @param studentGrade 学生年级（1–12），null 表示不过滤
     * @param gradeRange   知识点年级范围
     * @return true 表示匹配（或应当不过滤）
     */
    public static boolean matchesRange(Integer studentGrade, String gradeRange) {
        if (studentGrade == null || gradeRange == null || gradeRange.isBlank()) {
            return true;
        }
        if (!isValidRange(gradeRange)) {
            log.warn("年级范围非法，按全年级通用处理: gradeRange={}", gradeRange);
            return true;
        }
        for (String token : gradeRange.split(",", -1)) {
            String t = token.trim();
            if (t.contains("-")) {
                String[] parts = t.split("-", -1);
                int lo = Integer.parseInt(parts[0].trim());
                int hi = Integer.parseInt(parts[1].trim());
                if (studentGrade >= lo && studentGrade <= hi) {
                    return true;
                }
            } else if (Integer.parseInt(t) == studentGrade) {
                return true;
            }
        }
        return false;
    }

    /**
     * 学生年级是否匹配某道题
     *
     * <p>题目显式标注 {@code grade} 时按精确匹配（该题就是为这一年级准备的）；
     * 未标注时继承所属知识点的年级范围。</p>
     *
     * @param studentGrade 学生年级（1–12），null 表示不过滤
     * @param questionGrade 题目年级，null 表示继承知识点
     * @param gradeRange   所属知识点的年级范围
     * @return true 表示匹配
     */
    public static boolean matchesQuestion(Integer studentGrade, Integer questionGrade, String gradeRange) {
        if (studentGrade == null) {
            return true;
        }
        if (questionGrade != null) {
            if (!inRange(questionGrade)) {
                log.warn("题目年级超出 1-12，回退按知识点范围判断: questionGrade={}", questionGrade);
                return matchesRange(studentGrade, gradeRange);
            }
            return questionGrade.equals(studentGrade);
        }
        return matchesRange(studentGrade, gradeRange);
    }

    // ==================== 学段派生 ====================

    /**
     * 年级 → 学段：1–6 小学、7–9 初中、10–12 高中，其余返回 {@code null}
     *
     * <p>知识点/题目的学段优先由此派生（数据集中 {@code grade} 是一手字段），
     * 只有拿不到年级时才回退到解析课标编码。</p>
     *
     * @param grade 年级（1–12）
     * @return 学段中文名，或 null
     */
    public static String stageOf(Integer grade) {
        if (grade == null || !inRange(grade)) {
            return null;
        }
        if (grade <= 6) {
            return "小学";
        }
        return grade <= 9 ? "初中" : "高中";
    }

    /**
     * 年级范围 → 学段：范围内所有年级同属一个学段时返回该学段，
     * 跨学段（如 {@code "7-12"}）或无法解析时返回 {@code null}。
     *
     * @param gradeRange 年级范围（"3" / "7-9" / "1,2,3"）
     * @return 学段中文名，或 null
     */
    public static String stageOfRange(String gradeRange) {
        if (gradeRange == null || gradeRange.isBlank() || !isValidRange(gradeRange)) {
            return null;
        }
        String stage = null;
        for (String token : gradeRange.split(",", -1)) {
            String t = token.trim();
            Integer lo;
            Integer hi;
            if (t.contains("-")) {
                String[] parts = t.split("-", -1);
                lo = parseGrade(parts[0].trim());
                hi = parseGrade(parts[1].trim());
            } else {
                lo = parseGrade(t);
                hi = lo;
            }
            if (lo == null || hi == null) {
                return null;
            }
            for (int g = lo; g <= hi; g++) {
                String current = stageOf(g);
                if (stage == null) {
                    stage = current;
                } else if (!stage.equals(current)) {
                    // 范围跨越学段，无法判定单一学段
                    return null;
                }
            }
        }
        return stage;
    }

    /**
     * 精确年级匹配：{@code grade_range} 恰好等于该年级（如 {@code "10"}）
     *
     * <p>与 {@link #matchesRange} 的区别：这里用于**管理端按年级筛选**，
     * 语义是"核对标注"，因此 {@code "10-12"}、{@code "1,2,3"}、空值都**不**命中；
     * 而出题过滤仍用 {@link #matchesRange}（范围包含即适用）。</p>
     *
     * @param grade      年级（1–12）
     * @param gradeRange 知识点年级范围
     * @return 是否精确等于该年级
     */
    public static boolean matchesExactGrade(Integer grade, String gradeRange) {
        if (grade == null || gradeRange == null) {
            return false;
        }
        return String.valueOf(grade).equals(gradeRange.trim());
    }

    /**
     * 年级 → 面向 LLM / 用户展示的中文名称
     *
     * <p>Prompt 里传数字（{@code "12"}）会让模型按"12 年级"（美式）理解，
     * 传中文（{@code "高三"}）更准确。无法识别时原样返回，空值返回空串。</p>
     *
     * @param raw 原始年级（"12" / "高三" / "12年级" 等）
     * @return 中文年级名（"高三"），或原值
     */
    public static String displayName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Integer grade = normalizeStudentGrade(raw);
        if (grade == null) {
            return raw.trim();
        }
        return GRADE_LABELS[grade - 1];
    }

    /**
     * 1–12 对应的中文年级名
     */
    private static final String[] GRADE_LABELS = {
            "一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
            "初一", "初二", "初三", "高一", "高二", "高三"
    };

    // ==================== 内部工具 ====================

    private static boolean inRange(int grade) {
        return grade >= MIN_GRADE && grade <= MAX_GRADE;
    }

    /**
     * 解析单个年级：必须是 1–12 的纯数字，否则返回 null
     */
    private static Integer parseGrade(String text) {
        if (text == null || !DIGITS.matcher(text).matches()) {
            return null;
        }
        int g = Integer.parseInt(text);
        return inRange(g) ? g : null;
    }
}
