package com.jiang.service;

import com.jiang.mapper.ErrorQuestionMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错题 Mapper SQL 形态测试（Change 2026-11-11 <b>v3</b> 口径：负 ID 合法参与业务，只做防御）
 *
 * <p>v3 反转了 B6/N1 的"三处 question_id &gt; 0 过滤"：试卷错题现在与题库错题一视同仁参与
 * 原题重做与举一反三，因此到期查询/配额统计**不得**再过滤负 ID；KP_MAX 越界由上层的
 * {@code min(raw, KP_MAX)} 夹取消除。只有 {@code selectCriticalErrors}（调整 Agent 上下文）
 * 保留负 ID 防御并补 {@code student_id}（S10）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ErrorQuestion Mapper SQL 形态（v3）")
class ErrorQuestionMapperSqlTest {

    private static String selectSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ErrorQuestionMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }

    private static String updateSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ErrorQuestionMapper.class.getMethod(methodName, parameterTypes);
        Update update = method.getAnnotation(Update.class);
        return String.join(" ", update.value()).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("到期知识点 / 到期错题 / 配额统计 三处均不过滤负 ID（试卷错题一视同仁）")
    void should_notFilterNegativeIds_inDueAndQuotaQueries() throws Exception {
        String dueKpIds = selectSql("selectDueKnowledgePointIds", Long.class, String.class, LocalDate.class);
        String dueQuestions = selectSql("selectDueQuestionsByKnowledgePoint",
                Long.class, String.class, String.class, LocalDate.class);
        String activeCount = selectSql("selectActiveErrorCountGroupByKp", Long.class, String.class);

        assertFalse(dueKpIds.contains("question_id > 0"), "v3：到期知识点不得排除负 ID: " + dueKpIds);
        assertFalse(dueQuestions.contains("question_id > 0"), "v3：到期错题不得排除负 ID: " + dueQuestions);
        assertFalse(activeCount.contains("question_id > 0"), "v3：配额分母不得排除负 ID: " + activeCount);
        assertTrue(activeCount.contains("status = 'active'"), activeCount);
    }

    @Test
    @DisplayName("selectCriticalErrors：补 student_id 且保留 question_id > 0（S10 防御，非配额）")
    void should_scopeCriticalErrorsByStudent_andKeepNegativeIdGuard() throws Exception {
        String sql = selectSql("selectCriticalErrors", Long.class, LocalDate.class, String.class);

        assertTrue(sql.contains("student_id = #{studentId}"), "缺 student_id（跨学生泄漏）: " + sql);
        assertTrue(sql.contains("tenant_id = #{tenantId}"), sql);
        assertTrue(sql.contains("question_id > 0"), "负 ID 对调整 Agent 无意义，应保留防御: " + sql);
    }

    @Test
    @DisplayName("completeStageWithCheck：next_due_date 写 completedDate（列是 DATE NOT NULL，原 NULL 会报错）")
    void should_writeCompletedDate_when_completeStage() throws Exception {
        String sql = updateSql("completeStageWithCheck", Long.class, String.class,
                int.class, int.class, LocalDate.class);

        assertTrue(sql.contains("next_due_date = #{completedDate}"), sql);
        assertFalse(sql.contains("next_due_date = NULL"), "DATE NOT NULL 列不能写 NULL: " + sql);
        assertTrue(sql.contains("status = 'completed'"), sql);
    }

    @Test
    @DisplayName("错题本列表/详情：JOIN exam_question 兜底试卷题面/题型/答案/解析（S9）")
    void should_joinExamQuestion_forExamSourceDetail() throws Exception {
        String page = selectSql("selectPageWithDetail", Long.class, String.class, String.class,
                String.class, int.class, int.class);
        String detail = selectSql("selectDetailById", Long.class, String.class, Long.class);

        assertTrue(page.contains("LEFT JOIN exam_question eqx"), page);
        assertTrue(page.contains("eqx.id = -eq.question_id"), page);
        assertTrue(detail.contains("LEFT JOIN exam_question eqx"), detail);
        assertTrue(detail.contains("COALESCE(qb.type, eqx.question_type"), detail);
        assertTrue(detail.contains("COALESCE(qb.answer"), detail);
    }

    @Test
    @DisplayName("举一反三 exam 兜底源题：JOIN exam_question 按 -question_id 反查，最早到期 LIMIT 1")
    void should_selectExamSourceQuestion_byNegativeRefId() throws Exception {
        String sql = selectSql("selectDueExamSourceQuestion", Long.class, String.class,
                String.class, LocalDate.class);

        assertTrue(sql.contains("JOIN exam_question eqx ON eqx.id = -eq.question_id"), sql);
        assertTrue(sql.contains("eq.status = 'active'"), sql);
        assertTrue(sql.contains("ORDER BY eq.next_due_date ASC, eq.error_question_id ASC"), sql);
        assertTrue(sql.contains("LIMIT 1"), sql);
        assertFalse(sql.contains("select *"), sql);
    }

    @Test
    @DisplayName("列表查询参数顺序稳定（studentId, tenantId, kp 过滤, status, offset, size）")
    void should_keepPageQuerySignature() throws Exception {
        Method method = ErrorQuestionMapper.class.getMethod("selectPageWithDetail",
                Long.class, String.class, String.class, String.class, int.class, int.class);
        assertTrue(List.of(method.getParameterTypes()).contains(int.class));
    }
}
