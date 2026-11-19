package com.jiang.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作业读取的学生维度隔离（2026-11-19 缺陷回归）
 *
 * <p><b>缺陷</b>：{@code DailyPlanMapper.selectBySubjectAndDate} 的 WHERE 只有
 * {@code subject_id + tenant_id + plan_date}，且 {@code ORDER BY create_time DESC LIMIT 1}，
 * 会返回同租户内**该科目该日期任意学生**的计划 → 学生能看到别人的作业题面与作答
 * （每日作业页出现"自己没选的科目 + 只读 + 0/15"就是它）。</p>
 *
 * <p>本测试用反射把"必须有 student_id 过滤"钉死，防止再被改回去。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@DisplayName("作业读取 SQL 的学生隔离")
class HomeworkIsolationSqlTest {

    private static String selectSql(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws Exception {
        Method method = mapper.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("按科目+日期取当日计划：必须 JOIN learning_goal 并用 lg.student_id 过滤（防跨学生读）")
    void should_filterPlanByStudentId() throws Exception {
        String sql = selectSql(DailyPlanMapper.class, "selectBySubjectAndDate",
                Long.class, Long.class, String.class, LocalDate.class);

        assertTrue(sql.contains("JOIN learning_goal lg"), "必须 JOIN learning_goal 取归属: " + sql);
        assertTrue(sql.contains("lg.student_id = #{studentId}"), "缺少学生维度过滤（跨学生泄漏）: " + sql);
        assertTrue(sql.contains("lg.subject_id = #{subjectId}"), sql);
        assertTrue(sql.contains("lg.tenant_id = #{tenantId}"), "JOIN 侧也要带租户: " + sql);
        assertTrue(sql.contains("dp.tenant_id = #{tenantId}"), sql);
        assertTrue(sql.contains("dp.plan_date = #{planDate}"), sql);
    }

    @Test
    @DisplayName("按 planId 取提交记录：也必须带 student_id（纵深防御，表已有该列与索引）")
    void should_filterSubmissionsByStudentId() throws Exception {
        String sql = selectSql(HomeworkSubmissionDetailMapper.class, "selectByPlanId",
                Long.class, Long.class, String.class);

        assertTrue(sql.contains("student_id = #{studentId}"), "提交记录读取缺少学生维度: " + sql);
        assertTrue(sql.contains("tenant_id = #{tenantId}"), sql);
        assertTrue(sql.contains("plan_id = #{planId}"), sql);
        // 内外两层都要过滤：外层 s1 与子查询
        int filters = sql.split("student_id = #\\{studentId}", -1).length - 1;
        assertTrue(filters >= 2, "内层子查询与外层都要带 student_id，实际出现 " + filters + " 次: " + sql);
        assertFalse(sql.toLowerCase().contains("select *"), sql);
    }
}
