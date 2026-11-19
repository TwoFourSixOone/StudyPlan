package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单道试卷题目（视觉解析 → 归因 → 落库的中间模型）
 *
 * <p>字段与 {@code prompts/schemas/exam_parse_schema.json} 一一对应，另加 {@link #sourceImageIndex}
 * 与归因结果字段。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ParsedQuestion {

    /** 来源图片序号（1 开始，用于按图序重排题号） */
    private int sourceImageIndex;

    /** 题号（图内题号；跨图重排后为整卷题号 1..N） */
    private Integer questionIndex;

    /**
     * 卷面印刷题号（2026-11-17：保留卷面题号，不再按图序重排；缺失为 null）
     *
     * <p>全部题目都有合法且不重复的 paperNo 时，questionIndex 直接取该值；否则回退 1..N。</p>
     */
    private Integer paperNo;

    /** 题干（纯文本，约定 #24） */
    private String stem;

    /** 题型：choice/numeric/short/self */
    private String formatType;

    /** 选择题模式：single/multi（非选择题为 null；2026-11-12 由模型按卷面标注给出） */
    private String choiceMode;

    /** 选项（标准形态 [{"label":"A","text":"..."}]，约定 #25；非选择题为空列表） */
    private List<Map<String, String>> choices;

    /** 标准答案（选择题为选项标签；缺失为 null） */
    private String correctAnswer;

    /** 学生作答（纯文本） */
    private String studentAnswer;

    /** 作答状态：answered/blank/unreadable */
    private String answerStatus;

    /** 难度声明：basic/standard/challenge（缺失为 null，解析层不兜底） */
    private String difficulty;

    /** 题目解析（纯文本） */
    private String explanation;

    /**
     * 该题是否有**卷面印刷的图形**（2026-11-12；学生自己画的草稿/解题图不算）
     */
    private Boolean hasPrintedFigure;

    /**
     * 配图区域（归一化坐标，相对来源图片：x/y/width/height ∈ [0,1]，2026-11-12）
     *
     * <p>模型判断该题含图形/图表/几何图时给出；服务端据此裁剪出题目配图。
     * null 表示无图或模型未给出可用区域。</p>
     */
    private Map<String, Double> figureRect;

    /**
     * OCR 建议的配图区域（2026-11-17 修复）：来自 GLM-OCR `label=image` 的 bbox 吸附结果，
     * **只作候选、不覆盖模型给出的 {@link #figureRect}**。
     *
     * <p>为什么要两套框：模型框可能框到题号/文字（二次确认会拒），OCR 框可能框到装饰图；
     * 服务端按「模型框 → OCR 框」顺序裁剪 + 二次确认，任一通过即采用，都不过才回退整页原图。</p>
     */
    private Map<String, Double> ocrFigureRect;

    /** 模型自报无法辨认的字段名 */
    private List<String> unclearFields;

    /** 归因得到知识点 ID（null 表示待人工归因） */
    private String knowledgePointId;

    /** 归因状态：pending_manual_review/completed（落库前的最终值） */
    private String attributionStatus;

    /** 选项文本是否可用（约定 #25：choice 至少 2 个有效选项） */
    public boolean hasUsableChoices() {
        return choices != null && choices.size() >= 2
                && choices.stream().allMatch(c -> c.get("text") != null && !c.get("text").isBlank());
    }

    /** 是否含配图区域 */
    public boolean hasFigure() {
        return figureRect != null && !figureRect.isEmpty();
    }
}
