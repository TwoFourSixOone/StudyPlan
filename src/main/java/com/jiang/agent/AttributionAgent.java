package com.jiang.agent;

import com.jiang.dto.ExamQuestionAttributionDTO;
import com.jiang.dto.ExamQuestionOcrDTO;

import java.util.List;

/**
 * 归因 Agent 接口
 * 将 OCR 识别的题目映射到知识点
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface AttributionAgent {

    /**
     * 对 OCR 识别结果进行知识点归因（原方法：内部自建候选清单，行为不变）
     *
     * @param ocrResults OCR 识别结果列表
     * @param subjectId  科目 ID
     * @param tenantId   租户 ID（Worker 线程无 StudentContext，需显式传入）
     * @return 归因结果列表（含知识点 ID）
     */
    List<ExamQuestionAttributionDTO> attribute(List<ExamQuestionOcrDTO> ocrResults, Long subjectId, String tenantId);

    /**
     * 归因兜底重载（v4 §5）：使用调用方传入的候选清单（attribution 切片 ≤500/片），不再自建全量查询
     *
     * <p>仅用于"视觉直出为空"的题（ExamParseService 调用）；返回的 ID 必须落在候选清单内，
     * 否则视为未命中（规则 #8 白名单）。</p>
     *
     * @param ocrResults OCR 适配结果（只含未决题）
     * @param candidates 候选知识点（同卷 grade-filtered 全集切片）
     * @param tenantId   租户 ID
     * @return 归因结果列表（未命中的题 attributionStatus='pending_manual_review'、kp=null）
     */
    List<ExamQuestionAttributionDTO> attribute(List<ExamQuestionOcrDTO> ocrResults,
                                               List<com.jiang.dto.KpCandidate> candidates,
                                               String tenantId);
}
