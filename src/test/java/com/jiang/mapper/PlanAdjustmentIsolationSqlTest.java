package com.jiang.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划调整的学生维度隔离（P0-2 缺陷回归，2026-11-19）
 *
 * <p><b>缺陷</b>：{@code plan_adjustment_diff} / {@code plan_adjustment_log} 只有
 * {@code goal_id}，学生归属在 {@code learning_goal.student_id}。学生端的 diff 详情 / 确认 /
 * 拒绝与调整历史此前只按 {@code tenant_id} 过滤，同租户内学生 A 拿到 B 的 diffId 即可读 B 的
 * 调整方案，甚至 confirm 后由 {@code applyDiff} 改写 B 的目标策略与未来 {@code daily_plan}；
 * 调整历史则返回整个租户的记录。</p>
 *
 * <p>本测试用反射把"必须有 student_id 过滤"钉死（与 {@code HomeworkIsolationSqlTest} 同手法），
 * 防止后续把学生维度条件删掉。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-19
 */
@DisplayName("计划调整 SQL 的学生隔离")
class PlanAdjustmentIsolationSqlTest {

    private static String selectSql(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws Exception {
        Method method = mapper.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("按 diffId 取归属当前学生的 diff：必须 JOIN learning_goal 并用 lg.student_id 过滤")
    void should_joinLearningGoalAndFilterByStudent_when_selectingDiffWithStudent() throws Exception {
        String sql = selectSql(PlanAdjustmentDiffMapper.class, "selectByIdWithStudent",
                Long.class, Long.class, String.class);

        assertTrue(sql.contains("FROM plan_adjustment_diff d"), "必须以 diff 表为主表: " + sql);
        assertTrue(sql.contains("JOIN learning_goal lg"), "必须 JOIN learning_goal 取归属: " + sql);
        assertTrue(sql.contains("d.goal_id = lg.goal_id"), "JOIN 条件必须是 goal_id: " + sql);
        assertTrue(sql.contains("d.id = #{diffId}"), sql);
        assertTrue(sql.contains("lg.student_id = #{studentId}"), "缺少学生维度过滤（跨学生泄漏）: " + sql);
        assertTrue(sql.contains("lg.tenant_id = #{tenantId}"), "JOIN 侧也要带租户: " + sql);
        assertTrue(sql.contains("d.tenant_id = #{tenantId}"), sql);
        // 归属校验结果直接映射实体，禁止 SELECT *（两张表都有 tenant_id/create_time，会串列）
        assertFalse(sql.toLowerCase().contains("select *"), sql);
    }

    @Test
    @DisplayName("触发调整前的目标归属校验：必须同时带 goal_id + student_id + tenant_id")
    void should_filterGoalOwnerByStudentAndTenant_when_selectingOwnedGoalId() throws Exception {
        String sql = selectSql(LearningGoalMapper.class, "selectOwnedGoalId",
                Long.class, Long.class, String.class);

        assertTrue(sql.contains("FROM learning_goal"), sql);
        assertTrue(sql.contains("goal_id = #{goalId}"), sql);
        assertTrue(sql.contains("student_id = #{studentId}"), "缺少学生归属条件（可对他人目标触发调整）: " + sql);
        assertTrue(sql.contains("tenant_id = #{tenantId}"), sql);
    }

    @Test
    @DisplayName("调整历史的 goalIds：必须按 student_id + tenant_id 取，且不过滤 status（历史要完整）")
    void should_filterGoalIdsByStudent_when_queryingHistoryGoals() throws Exception {
        String sql = selectSql(LearningGoalMapper.class, "selectGoalIdsByStudent",
                Long.class, String.class);

        assertTrue(sql.contains("FROM learning_goal"), sql);
        assertTrue(sql.startsWith("SELECT goal_id"), "只取 goal_id 即可，避免多列 + 反模式: " + sql);
        assertTrue(sql.contains("student_id = #{studentId}"), "缺少学生维度过滤（返回全租户历史）: " + sql);
        assertTrue(sql.contains("tenant_id = #{tenantId}"), sql);
        assertFalse(sql.contains("status"), "历史查询不得过滤 status，否则已结束目标的历史会丢失: " + sql);
    }

    @Test
    @DisplayName("归属 SQL 强护栏：JOIN / d.tenant_id / lg.student_id / lg.tenant_id 缺任一即判红（含弱化形态反证）")
    void should_rejectSqlWithoutAnyStudentOrTenantFilter_when_selectingDiffWithStudent() throws Exception {
        String sql = selectSql(PlanAdjustmentDiffMapper.class, "selectByIdWithStudent",
                Long.class, Long.class, String.class);

        // 1) 真实 SQL 必须同时具备 4 个归属/租户片段（与第 1 例同口径，作为独立的强护栏）
        assertTrue(hasOwnershipGuard(sql), "归属 SQL 缺必需片段（删任一 student/tenant 条件都应判红）: " + sql);

        // 2) 反证：任一条件缺失的"弱化形态"都必须被同一断言判红 —— 证明删条件不会静默通过
        String withoutJoin = sql.replace("JOIN learning_goal lg", "");
        String withoutStudentFilter = sql.replace("lg.student_id = #{studentId}", "");
        String withoutGoalJoinCondition = sql.replace("d.goal_id = lg.goal_id", "");
        String withoutJoinTenant = sql.replace("lg.tenant_id = #{tenantId}", "");
        String withoutDiffTenant = sql.replace("d.tenant_id = #{tenantId}", "");
        // 历史弱化形态：只有 d.id + d.tenant_id、完全没有 learning_goal 归属
        String legacyTenantOnlySql = "SELECT d.id, d.goal_id, d.status, d.diff_content, d.tenant_id "
                + "FROM plan_adjustment_diff d WHERE d.id = #{diffId} AND d.tenant_id = #{tenantId}";

        for (String weakened : List.of(withoutJoin, withoutStudentFilter, withoutGoalJoinCondition,
                withoutJoinTenant, withoutDiffTenant, legacyTenantOnlySql)) {
            assertFalse(hasOwnershipGuard(weakened), "弱化形态（缺 student/tenant 归属条件）必须判红: " + weakened);
        }
    }

    /**
     * 归属 SQL 的强护栏口径：JOIN 归属表 + 两侧租户 + 学生维度，缺一不可
     */
    private static boolean hasOwnershipGuard(String sql) {
        return sql.contains("JOIN learning_goal lg")
                && sql.contains("d.goal_id = lg.goal_id")
                && sql.contains("d.id = #{diffId}")
                && sql.contains("d.tenant_id = #{tenantId}")
                && sql.contains("lg.student_id = #{studentId}")
                && sql.contains("lg.tenant_id = #{tenantId}");
    }
}
