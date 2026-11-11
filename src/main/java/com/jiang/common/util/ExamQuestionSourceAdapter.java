package com.jiang.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.model.entity.ExamQuestion;
import com.jiang.model.entity.QuestionBank;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 试卷题 → 临时 {@link QuestionBank} 适配器（design v3 §7.2.1 / tasks D6）
 *
 * <p>举一反三源题策略是「题库优先 + exam 兜底，均不落库」：当某知识点只有试卷错题
 * （{@code question_bank} 无题）时，用本适配器把 {@code exam_question} 映射成
 * <b>transient（临时、不 insert）</b> 的 QuestionBank，交给既有
 * {@code SimilarQuestionService.generateWithValidation(...)}（签名零改动）。</p>
 *
 * <p><b>不写 question_bank 副本</b>：违反规则 #10（题库只经 JSON 导入 + 人工审核）、
 * 同一道题按学生重复落库、混淆 generated 语义。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Slf4j
public final class ExamQuestionSourceAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ExamQuestionSourceAdapter() {
    }

    /**
     * 临时 QuestionBank（不落库）；题干为空时返回 null（无题面无法作为源题）
     *
     * @param examQuestion 试卷题（question_content 为 codec 编码文本）
     * @return 临时 QuestionBank（questionId 为**正数** exam id，仅作内存标识，不会被当题库 ID 查库）
     */
    public static QuestionBank toQuestionBank(ExamQuestion examQuestion) {
        if (examQuestion == null) {
            return null;
        }
        ExamQuestionTextCodec.Decoded decoded = ExamQuestionTextCodec.decode(examQuestion.getQuestionContent());
        String stem = LlmTextSanitizer.sanitize(decoded.stem());
        if (stem == null || stem.isBlank()) {
            log.warn("试卷题无题干，无法作为举一反三源题: examQuestionId={}", examQuestion.getId());
            return null;
        }

        QuestionBank questionBank = new QuestionBank();
        questionBank.setQuestionId(examQuestion.getId());
        questionBank.setKnowledgePointId(examQuestion.getKnowledgePointId());
        questionBank.setContent(buildContent(stem, decoded.choices()));
        questionBank.setAnswer(decoded.correctAnswer());
        questionBank.setAnalysis(LlmTextSanitizer.sanitize(decoded.explanation()));
        questionBank.setType(examQuestion.getQuestionType());
        questionBank.setDifficulty(examQuestion.getDifficulty());
        questionBank.setGrade(null);
        questionBank.setChoices(toChoicesJson(decoded.choices()));
        questionBank.setTenantId(examQuestion.getTenantId());
        return questionBank;
    }

    /**
     * 源题是否可用来生成举一反三：必须能解出非空标准答案
     *
     * <p>视觉解析未能解出答案时（草稿/缺答案）→ 调用方跳过该知识点并写 {@code agent_degradation_log}，
     * 不阻塞其他知识点（design §7.2.1-5）。</p>
     */
    public static boolean hasUsableAnswer(ExamQuestion examQuestion) {
        if (examQuestion == null) {
            return false;
        }
        String answer = ExamQuestionTextCodec.decode(examQuestion.getQuestionContent()).correctAnswer();
        return answer != null && !answer.isBlank();
    }

    /**
     * 源题纯文本：题干 + 选项行（纯文本，约定 #24；选项只出文本，label 由前端/下一步渲染，约定 #25）
     */
    static String buildContent(String stem, List<Map<String, String>> choices) {
        StringBuilder sb = new StringBuilder(stem);
        if (choices != null) {
            for (Map<String, String> option : choices) {
                if (option == null) {
                    continue;
                }
                String text = LlmTextSanitizer.sanitize(option.get("text"));
                if (text == null || text.isBlank()) {
                    continue;
                }
                sb.append('\n').append(text);
            }
        }
        return sb.toString();
    }

    /**
     * 选项转 JSON 字符串（QuestionBank.choices 的存储形态；无选项返回 null）
     */
    static String toChoicesJson(List<Map<String, String>> choices) {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(choices);
        } catch (Exception e) {
            log.warn("试卷题选项序列化失败，按无选项处理: {}", e.getMessage());
            return null;
        }
    }
}
