package com.jiang.service;

import com.jiang.dto.ExamQuestionOcrDTO;

import java.util.List;

/**
 * OCR 服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface OcrService {

    /**
     * 识别试卷图片中的题目
     *
     * @param imageUrl 图片 URL
     * @return 题目列表（含 OCR 识别结果）
     */
    List<ExamQuestionOcrDTO> recognize(String imageUrl);
}
