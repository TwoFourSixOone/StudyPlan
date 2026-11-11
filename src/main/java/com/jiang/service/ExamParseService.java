package com.jiang.service;

import com.jiang.dto.ExamParseOutcome;

import java.util.List;

/**
 * 试卷视觉解析编排服务（整卷维度）
 *
 * <p>把 design §3.5/§3.6 的编排逻辑收在这里：逐图并发解析（有界池 + deadline）、
 * 题号重排 1..N、跨图去重、总题数截断、归因分批与降级。</p>
 *
 * <p>本服务<b>不触碰数据库</b>：入参显式传 paperId/subjectId/studentId/tenantId，
 * 返回的 {@link ExamParseOutcome} 由 Worker 在 TX-A 中批量落库，因此 LLM 调用与 DB 写不在同一事务里（约定 #10）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public interface ExamParseService {

    /**
     * 解析整卷
     *
     * @param examPaperId 试卷 ID（日志/降级摘要用）
     * @param subjectId   科目 ID（归因 Agent 入参）
     * @param studentId   学生 ID（取年级拼 Prompt）
     * @param tenantId    租户 ID（显式入参，Worker 不读 ThreadLocal）
     * @param imageUrls   试卷图片 URL（按上传顺序）
     * @return 整卷解析结果（题目已重排题号并完成归因）
     */
    ExamParseOutcome parsePaper(Long examPaperId, Long subjectId, Long studentId,
                                String tenantId, List<String> imageUrls);
}
