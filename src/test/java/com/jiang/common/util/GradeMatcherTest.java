package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GradeMatcher 单元测试（产品规则 #11）
 *
 * <p>重点覆盖两类"静默失效"风险：</p>
 * <ul>
 *   <li>学生年级字符串形态多样（数字/中文/学段），归一化失败会导致过滤整体失效</li>
 *   <li>年级范围取值非法（0、13、倒序）入库会让知识点变成"内容黑洞"</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@DisplayName("GradeMatcher 年级匹配")
class GradeMatcherTest {

    // ==================== normalizeStudentGrade ====================

    @Nested
    @DisplayName("normalizeStudentGrade")
    class NormalizeStudentGrade {

        @Test
        @DisplayName("纯数字：'9' 与带空格的 ' 3 '")
        void should_parseDigits() {
            assertEquals(9, grade("9"));
            assertEquals(3, grade(" 3 "));
            assertEquals(12, grade("12"));
        }

        @Test
        @DisplayName("数字 + 年级后缀：'3年级' / '7级'")
        void should_parseDigitsWithSuffix() {
            assertEquals(3, grade("3年级"));
            assertEquals(7, grade("7级"));
        }

        @Test
        @DisplayName("中文数字：'三年级' / '六年级'")
        void should_parseChineseGrade() {
            assertEquals(3, grade("三年级"));
            assertEquals(6, grade("六年级"));
        }

        @Test
        @DisplayName("初高中别名：初一/初二/初三、高一/高三")
        void should_parseAlias() {
            assertEquals(7, grade("初一"));
            assertEquals(8, grade("初二"));
            assertEquals(9, grade("初三"));
            assertEquals(10, grade("高一"));
            assertEquals(12, grade("高三"));
        }

        @Test
        @DisplayName("学段级（小学/初中/高中）无法定位单一年级 → null")
        void should_returnNull_when_stageLevel() {
            assertNull(GradeMatcher.normalizeStudentGrade("小学"));
            assertNull(GradeMatcher.normalizeStudentGrade("初中"));
            assertNull(GradeMatcher.normalizeStudentGrade("高中"));
        }

        @Test
        @DisplayName("空值/无法识别/越界 → null")
        void should_returnNull_when_blankOrInvalid() {
            assertNull(GradeMatcher.normalizeStudentGrade(null));
            assertNull(GradeMatcher.normalizeStudentGrade(""));
            assertNull(GradeMatcher.normalizeStudentGrade("   "));
            assertNull(GradeMatcher.normalizeStudentGrade("abc"));
            assertNull(GradeMatcher.normalizeStudentGrade("0"));
            assertNull(GradeMatcher.normalizeStudentGrade("13"));
            assertNull(GradeMatcher.normalizeStudentGrade("99"));
        }
    }

    // ==================== isValidRange ====================

    @Nested
    @DisplayName("isValidRange")
    class IsValidRange {

        @Test
        @DisplayName("null / 空白 → 合法（字段可选）")
        void should_beValid_when_blank() {
            assertTrue(GradeMatcher.isValidRange(null));
            assertTrue(GradeMatcher.isValidRange(""));
            assertTrue(GradeMatcher.isValidRange("   "));
        }

        @Test
        @DisplayName("单值 / 区间 / 列表 → 合法")
        void should_beValid_when_normalForms() {
            assertTrue(GradeMatcher.isValidRange("3"));
            assertTrue(GradeMatcher.isValidRange("7-9"));
            assertTrue(GradeMatcher.isValidRange("1,2,3"));
            assertTrue(GradeMatcher.isValidRange("3, 7-9, 12"));
            assertTrue(GradeMatcher.isValidRange("1"));
            assertTrue(GradeMatcher.isValidRange("12"));
        }

        @Test
        @DisplayName("取值越界（0 / 13 / 99）→ 非法，不能只靠正则")
        void should_beInvalid_when_outOfRange() {
            assertFalse(GradeMatcher.isValidRange("0"));
            assertFalse(GradeMatcher.isValidRange("13"));
            assertFalse(GradeMatcher.isValidRange("99"));
            assertFalse(GradeMatcher.isValidRange("0-6"));
            assertFalse(GradeMatcher.isValidRange("7-13"));
        }

        @Test
        @DisplayName("区间倒序 '9-7' → 非法")
        void should_beInvalid_when_reversedRange() {
            assertFalse(GradeMatcher.isValidRange("9-7"));
        }

        @Test
        @DisplayName("格式异常：尾逗号 / 空段 / 多级区间 / 全角破折号 / 非数字")
        void should_beInvalid_when_malformed() {
            assertFalse(GradeMatcher.isValidRange("1,2,3,"));
            assertFalse(GradeMatcher.isValidRange("1,,2"));
            assertFalse(GradeMatcher.isValidRange("1-3-5"));
            assertFalse(GradeMatcher.isValidRange("7—9"));
            assertFalse(GradeMatcher.isValidRange("abc"));
            assertFalse(GradeMatcher.isValidRange("-1"));
        }
    }

    // ==================== matchesRange ====================

    @Nested
    @DisplayName("matchesRange")
    class MatchesRange {

        @Test
        @DisplayName("学生年级为空 → 不过滤")
        void should_pass_when_studentGradeNull() {
            assertTrue(GradeMatcher.matchesRange(null, "3"));
            assertTrue(GradeMatcher.matchesRange(null, null));
        }

        @Test
        @DisplayName("范围为空 → 不过滤（全年级通用）")
        void should_pass_when_rangeBlank() {
            assertTrue(GradeMatcher.matchesRange(5, null));
            assertTrue(GradeMatcher.matchesRange(5, ""));
            assertTrue(GradeMatcher.matchesRange(5, "  "));
        }

        @Test
        @DisplayName("单值：仅匹配该年级")
        void should_matchExactGrade() {
            assertTrue(GradeMatcher.matchesRange(3, "3"));
            assertFalse(GradeMatcher.matchesRange(4, "3"));
        }

        @Test
        @DisplayName("区间：边界闭区间")
        void should_matchRangeInclusive() {
            assertTrue(GradeMatcher.matchesRange(7, "7-9"));
            assertTrue(GradeMatcher.matchesRange(9, "7-9"));
            assertFalse(GradeMatcher.matchesRange(6, "7-9"));
            assertFalse(GradeMatcher.matchesRange(10, "7-9"));
        }

        @Test
        @DisplayName("列表：命中任一元素")
        void should_matchList() {
            assertTrue(GradeMatcher.matchesRange(2, "1,2,3"));
            assertFalse(GradeMatcher.matchesRange(4, "1,2,3"));
        }

        @Test
        @DisplayName("范围非法 → 降级为不过滤（不能把池子筛空）")
        void should_pass_when_rangeInvalid() {
            assertTrue(GradeMatcher.matchesRange(5, "9-7"));
            assertTrue(GradeMatcher.matchesRange(5, "0"));
            assertTrue(GradeMatcher.matchesRange(5, "abc"));
        }
    }

    // ==================== matchesQuestion ====================

    @Nested
    @DisplayName("matchesQuestion")
    class MatchesQuestion {

        @Test
        @DisplayName("学生年级为空 → 不过滤")
        void should_pass_when_studentGradeNull() {
            assertTrue(GradeMatcher.matchesQuestion(null, 3, "7-9"));
        }

        @Test
        @DisplayName("题目显式标注年级 → 精确匹配")
        void should_matchExact_when_questionGradePresent() {
            assertTrue(GradeMatcher.matchesQuestion(3, 3, "1-6"));
            assertFalse(GradeMatcher.matchesQuestion(4, 3, "1-6"));
        }

        @Test
        @DisplayName("题目未标注年级 → 继承知识点范围")
        void should_fallbackToRange_when_questionGradeNull() {
            assertTrue(GradeMatcher.matchesQuestion(8, null, "7-9"));
            assertFalse(GradeMatcher.matchesQuestion(10, null, "7-9"));
        }

        @Test
        @DisplayName("题目年级越界（如 99）→ 回退按知识点范围判断")
        void should_fallbackToRange_when_questionGradeInvalid() {
            assertTrue(GradeMatcher.matchesQuestion(3, 99, "3"));
            assertFalse(GradeMatcher.matchesQuestion(4, 99, "3"));
        }
    }

    // ==================== 学段派生 ====================

    @Nested
    @DisplayName("stageOf / stageOfRange")
    class StageDerivation {

        @Test
        @DisplayName("grade → 学段：1-6 小学 / 7-9 初中 / 10-12 高中")
        void should_mapGradeToStage() {
            assertEquals("小学", GradeMatcher.stageOf(1));
            assertEquals("小学", GradeMatcher.stageOf(6));
            assertEquals("初中", GradeMatcher.stageOf(7));
            assertEquals("初中", GradeMatcher.stageOf(9));
            assertEquals("高中", GradeMatcher.stageOf(10));
            assertEquals("高中", GradeMatcher.stageOf(12));
        }

        @Test
        @DisplayName("grade 越界或为空 → null")
        void should_returnNull_when_gradeInvalid() {
            assertNull(GradeMatcher.stageOf(0));
            assertNull(GradeMatcher.stageOf(13));
            assertNull(GradeMatcher.stageOf(null));
        }

        @Test
        @DisplayName("年级范围 → 学段：同段返回，跨段返回 null")
        void should_mapRangeToStage() {
            assertEquals("小学", GradeMatcher.stageOfRange("3"));
            assertEquals("小学", GradeMatcher.stageOfRange("1-6"));
            assertEquals("小学", GradeMatcher.stageOfRange("1,2,3"));
            assertEquals("初中", GradeMatcher.stageOfRange("7-9"));
            assertEquals("高中", GradeMatcher.stageOfRange("10-12"));
            // 跨学段无法判定单一学段
            assertNull(GradeMatcher.stageOfRange("7-12"));
            assertNull(GradeMatcher.stageOfRange("1,7"));
        }

        @Test
        @DisplayName("年级范围为空/非法 → null")
        void should_returnNull_when_rangeInvalid() {
            assertNull(GradeMatcher.stageOfRange(null));
            assertNull(GradeMatcher.stageOfRange(""));
            assertNull(GradeMatcher.stageOfRange("9-7"));
            assertNull(GradeMatcher.stageOfRange("abc"));
        }
    }

    @Nested
    @DisplayName("matchesExactGrade（管理端筛选：精确匹配）")
    class MatchesExactGrade {

        @Test
        @DisplayName("只有单年级标注才命中")
        void should_matchOnlyExactSingleGrade() {
            assertTrue(GradeMatcher.matchesExactGrade(10, "10"));
            assertTrue(GradeMatcher.matchesExactGrade(10, " 10 "));
            assertFalse(GradeMatcher.matchesExactGrade(10, "10-12"), "范围标注不命中精确筛选");
            assertFalse(GradeMatcher.matchesExactGrade(10, "1,2,3"));
        }

        @Test
        @DisplayName("空值与非法值都不命中")
        void should_notMatch_when_blankOrInvalid() {
            assertFalse(GradeMatcher.matchesExactGrade(10, null));
            assertFalse(GradeMatcher.matchesExactGrade(10, ""));
            assertFalse(GradeMatcher.matchesExactGrade(10, "abc"));
            assertFalse(GradeMatcher.matchesExactGrade(null, "10"));
        }
    }

    @Nested
    @DisplayName("displayName（面向 LLM 的中文年级）")
    class GradeDisplayName {

        @Test
        @DisplayName("数字编码转中文名")
        void should_mapCodeToChinese() {
            assertEquals("高三", GradeMatcher.displayName("12"));
            assertEquals("初三", GradeMatcher.displayName("9"));
            assertEquals("三年级", GradeMatcher.displayName("3"));
        }

        @Test
        @DisplayName("已是中文或无法识别时原样返回")
        void should_keepRaw_when_notNumeric() {
            assertEquals("高三", GradeMatcher.displayName("高三"));
            assertEquals("初中", GradeMatcher.displayName("初中"));
        }

        @Test
        @DisplayName("空值返回空串")
        void should_returnEmpty_when_blank() {
            assertEquals("", GradeMatcher.displayName(null));
            assertEquals("", GradeMatcher.displayName(""));
        }
    }

    // ==================== 辅助方法 ====================

    private int grade(String raw) {
        Integer g = GradeMatcher.normalizeStudentGrade(raw);
        assertNotNull(g, "归一化结果不应为 null: " + raw);
        return g;
    }
}
