package com.jiang.agent;

import com.jiang.dto.ExamParseContext;
import com.jiang.dto.ExamParseImageResult;
import com.jiang.dto.ExamParsePaperContext;
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

    /**
     * 整卷解析（2026-11-12 v5）：全部图片一次性发给视觉模型
     *
     * <p>解决三类问题：① 题目/图形跨页；② 学生答案写在答题卡（另一张图）；③ 多选按卷面判断。
     * 模型输出全卷连续题号与每题所在图 {@code imageIndex}；失败时由调用方回退逐图解析。</p>
     *
     * @param images  全部图片（按上传顺序，1..N）
     * @param context 整卷上下文（学科/年级/图片总数/候选知识点）
     * @return 整卷结果（questions 可能为空；failureReason 非空表示重试耗尽）
     */
    ExamParseImageResult parsePaper(java.util.List<VisionImage> images, ExamParsePaperContext context);

    /**
     * 印刷题目版解析（2026-11-17 双版本）：只读印刷内容，studentAnswer 固定空/blank
     *
     * @param images  clean 图（去红/蓝/绿后的图；黑笔卷可能等同原图）
     * @param context 整卷上下文（含 OCR 文本与候选知识点）
     */
    ExamParseImageResult parseCleanPaper(java.util.List<VisionImage> images,
                                         ExamParsePaperContext context);

    /**
     * 学生作答版解析（2026-11-17 双版本）：只提取作答，按 paperNo 对齐
     *
     * @param images  student 图（去红后的图，保留学生黑/蓝笔迹）
     * @param context 作答上下文（题目清单 + 重试提示）
     */
    com.jiang.dto.ExamAnswerParseResult parseStudentAnswers(
            java.util.List<VisionImage> images, com.jiang.dto.ExamAnswerParseContext context);
}
