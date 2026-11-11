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

    /** 题干（纯文本，约定 #24） */
    private String stem;

    /** 题型：choice/numeric/short/self */
    private String formatType;

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
}
