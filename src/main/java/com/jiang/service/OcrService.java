package com.jiang.service;

import com.jiang.dto.ExamQuestionOcrDTO;

import java.util.List;

/**
 * OCR 服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 * @deprecated 试卷链路已改为视觉模型解析（Change 2026-11-11_exam_paper_upload_llm，design D8）：
 *         {@code ExamParseAgent} 不再依赖 OCR。本接口与实现保留但不注入、不调用，
 *         删除需连带清理 SDK 依赖与密钥配置，属独立 change（design §17-1）。
 */
@Deprecated
public interface OcrService {

    /**
     * 识别试卷图片中的题目
     *
     * @param imageUrl 图片 URL
     * @return 题目列表（含 OCR 识别结果）
     * @deprecated 见类注释
     */
    @Deprecated
    List<ExamQuestionOcrDTO> recognize(String imageUrl);
}
