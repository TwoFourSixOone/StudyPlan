package com.jiang.agent;

import com.jiang.dto.ExamParseContext;
import com.jiang.dto.ExamParseImageResult;
import com.jiang.dto.VisionImage;

/**
 * 试卷视觉解析 Agent（单图）
 *
 * <p>职责：拼 Prompt → 调用视觉模型（{@code AgentScopeClient#callVision}）→ 校验/清洗/归一化 →
 * 返回结构化题目。单图最多重试 2 次（总尝试 3 次），重试计数<b>只存在于内存</b>，
 * 不写 {@code ocr_retry_count}（design D14/B3）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
public interface ExamParseAgent {

    /**
     * 解析一张试卷图片
     *
     * @param image   图片（媒体类型 + 字节，调用后即可释放）
     * @param context 上下文（学科、年级、图序、重试提示）
     * @return 单图解析结果（整图失败时 questions 为空且 failureReason 非空）
     */
    ExamParseImageResult parseImage(VisionImage image, ExamParseContext context);
}
