package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「同段且不高于学生年级」匹配测试（2026-11-12，考试归因候选口径）
 */
@DisplayName("GradeMatcher.matchesRangeAtOrBelow")
class GradeMatcherAtOrBelowTest {

    @Test
    @DisplayName("高三：包含高一/高二/高三/10-12，排除初中/小学")
    void should_matchSameStageAtOrBelow() {
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(12, "10"), "高一知识（集合等）应可归因");
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(12, "11"));
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(12, "12"));
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(12, "10-12"));
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(12, "10,11"), "逗号分隔范围同样适用");

        assertFalse(GradeMatcher.matchesRangeAtOrBelow(12, "9"), "初中知识不应进入高中候选");
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(12, "7-9"));
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(12, "3"));
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(12, "7-12"), "跨学段范围无法判定单一学段 → 不匹配");
    }

    @Test
    @DisplayName("高一：不提前使用高二/高三知识")
    void should_notIncludeHigherGrades() {
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(10, "10"));
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(10, "11"));
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(10, "12"));
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(10, "10-12"), "含高二/高三 → 不适用高一");
    }

    @Test
    @DisplayName("初一：包含小学（同段内更低年级）？否——小学不同学段")
    void should_respectStageBoundary() {
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(7, "7"));
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(7, "6"), "小学知识不应进入初中候选");
        assertFalse(GradeMatcher.matchesRangeAtOrBelow(7, "10"));
    }

    @Test
    @DisplayName("学生年级/范围为空 → 不过滤（全库降级）")
    void should_notFilter_whenStudentGradeOrRangeMissing() {
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(null, "7-9"));
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(12, null));
        assertTrue(GradeMatcher.matchesRangeAtOrBelow(12, "  "));
    }
}
