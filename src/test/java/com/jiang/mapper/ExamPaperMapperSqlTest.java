package com.jiang.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史试卷读接口 SQL 形态测试（design §10.1/§10.2：固定 2/3 条 SQL、显式列名、必有 tenant_id）
 *
 * <p>零 DDL + 只读接口的约束落在 SQL 层，用反射把关键形态固定住，防止后续改动回退为
 * {@code select *}、漏掉租户条件或丢掉排序/派生表。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamPaper 读接口 SQL 形态")
class ExamPaperMapperSqlTest {

    private static String selectSql(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws Exception {
        Method method = mapper.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).replaceAll("\\s+", " ");
    }

    private static String updateSql(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws Exception {
        Method method = mapper.getMethod(methodName, parameterTypes);
        Update update = method.getAnnotation(Update.class);
        return String.join(" ", update.value()).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("A3：追加图片带 JSON_LENGTH < maxImages 条件（并发 TOCTOU 守卫）+ uploading 状态条件")
    void should_guardAppendImageUrl_withJsonLength() throws Exception {
        String sql = updateSql(ExamPaperMapper.class, "appendImageUrl",
                Long.class, String.class, String.class, int.class, int.class);

        assertTrue(sql.contains("JSON_ARRAY_APPEND(image_urls, '$', #{imageUrl})"), sql);
        assertTrue(sql.contains("image_page_nos = JSON_ARRAY_APPEND(COALESCE(image_page_nos, JSON_ARRAY()), '$', #{pageNo})"),
                "页序必须与 URL 同语句追加，实际 SQL: " + sql);
        assertTrue(sql.contains("JSON_LENGTH(image_urls) < #{maxImages}"),
                "缺少并发张数守卫（A3），实际 SQL: " + sql);
        assertTrue(sql.contains("status = 'uploading'"), sql);
        assertTrue(sql.contains("tenant_id = #{tenantId}"), sql);
    }

    @Test
    @DisplayName("2026-11-17：两处重置会话必须同时清空 image_page_nos（否则页序长度与 URL 不一致）")
    void should_resetImagePageNos_alongWithUrls() throws Exception {
        String resetReuse = updateSql(ExamPaperMapper.class, "resetImageUrls", Long.class, String.class);
        assertTrue(resetReuse.contains("image_urls = JSON_ARRAY()"), resetReuse);
        assertTrue(resetReuse.contains("image_page_nos = JSON_ARRAY()"),
                "30 分钟超期复用必须同时清空页序，实际 SQL: " + resetReuse);

        String resetFailed = updateSql(ExamPaperMapper.class, "resetFailedPaperForReupload",
                Long.class, String.class);
        assertTrue(resetFailed.contains("image_urls = JSON_ARRAY()"), resetFailed);
        assertTrue(resetFailed.contains("image_page_nos = JSON_ARRAY()"),
                "失败当天重传必须同时清空页序，实际 SQL: " + resetFailed);
    }

    @Test
    @DisplayName("列表 count：带 student_id + tenant_id，不使用 select *")
    void should_countWithTenantFilter() throws Exception {
        String sql = selectSql(ExamPaperMapper.class, "countByStudent", Long.class, String.class);

        assertTrue(sql.contains("COUNT(*)"), sql);
        assertTrue(sql.contains("student_id = #{studentId}"), sql);
        assertTrue(sql.contains("tenant_id = #{tenantId}"), sql);
        assertFalse(sql.toLowerCase().contains("select *"), sql);
    }

    @Test
    @DisplayName("列表分页：派生表聚合 + 考试日期倒序 + LIMIT offset,size + 显式列名")
    void should_pageWithDerivedAggregate() throws Exception {
        String sql = selectSql(ExamPaperMapper.class, "selectPageWithStats",
                Long.class, String.class, int.class, int.class);
        String lower = sql.toLowerCase();

        assertTrue(lower.contains("select p.id as paperid"), sql);
        assertTrue(lower.contains("left join subject s"), sql);
        assertTrue(lower.contains("group by q.exam_paper_id"), sql);
        assertTrue(lower.contains("sum(case when q.is_correct = 1 then 1 else 0 end) as correctcount"), sql);
        assertTrue(lower.contains("sum(case when q.is_correct is not null then 1 else 0 end) as judgedcount"),
                sql);
        assertTrue(lower.contains("order by p.exam_date desc, p.id desc"), sql);
        assertTrue(lower.contains("limit #{offset}, #{size}"), sql);
        assertTrue(lower.contains("p.student_id = #{studentid}"), sql);
        assertTrue(lower.contains("p.tenant_id = #{tenantid}"), sql);
        assertFalse(lower.contains("select *"), sql);
    }

    @Test
    @DisplayName("详情 paper：按 id + student_id + tenant_id 三条件查询（归属校验内嵌 SQL）")
    void should_selectDetailWithOwnership() throws Exception {
        String sql = selectSql(ExamPaperMapper.class, "selectDetailWithSubject",
                Long.class, Long.class, String.class);

        assertTrue(sql.contains("p.id = #{paperId}"), sql);
        assertTrue(sql.contains("p.student_id = #{studentId}"), sql);
        assertTrue(sql.contains("p.tenant_id = #{tenantId}"), sql);
        assertTrue(sql.contains("s.name AS subjectName"), sql);
        assertFalse(sql.toLowerCase().contains("select *"), sql);
    }

    @Test
    @DisplayName("详情题目：按题号升序 + tenant_id 过滤（命中 idx_paper_index，无 filesort）")
    void should_selectQuestionsOrderedByIndex() throws Exception {
        String sql = selectSql(ExamQuestionMapper.class, "selectByPaperOrderByIndex",
                Long.class, String.class);

        assertTrue(sql.contains("WHERE exam_paper_id = #{examPaperId} AND tenant_id = #{tenantId}"), sql);
        assertTrue(sql.contains("ORDER BY question_index ASC"), sql);
        assertFalse(sql.toLowerCase().contains("select *"), sql);
    }

    @Test
    @DisplayName("详情知识点批量：IN + tenant_id（PRIMARY 范围扫描）")
    void should_selectKpNamesWithTenant() throws Exception {
        String sql = selectSql(KnowledgePointMapper.class, "selectNamesByIds",
                java.util.List.class, String.class);

        assertTrue(sql.contains("WHERE tenant_id = #{tenantId}"), sql);
        assertTrue(sql.contains("knowledge_point_id IN"), sql);
        assertFalse(sql.toLowerCase().contains("select *"), sql);
    }
}
