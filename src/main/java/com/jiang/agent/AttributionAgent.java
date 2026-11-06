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
     * 对 OCR 识别结果进行知识点归因
     *
     * @param ocrResults OCR 识别结果列表
     * @param subjectId  科目 ID
     * @param tenantId   租户 ID（Worker 线程无 StudentContext，需显式传入）
     * @return 归因结果列表（含知识点 ID）
     */
    List<ExamQuestionAttributionDTO> attribute(List<ExamQuestionOcrDTO> ocrResults, Long subjectId, String tenantId);
}
