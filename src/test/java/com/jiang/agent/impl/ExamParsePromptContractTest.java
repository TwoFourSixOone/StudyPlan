package com.jiang.agent.impl;

import com.jiang.dto.KpCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析 Prompt 契约测试（design v4 §3.2/§3.7 / tasks E2）
 *
 * <p>只校验契约文字（占位符、候选区块、数学记号规则），不发 LLM 请求。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("exam_parse_v1 Prompt 契约（v4）")
class ExamParsePromptContractTest {

    private String prompt() throws Exception {
        try (InputStream is = new ClassPathResource("prompts/versions/exam_parse_v1.txt").getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("包含全部占位符（含 v4 新增 {knowledgePoints}）")
    void should_containAllPlaceholders() throws Exception {
        String text = prompt();
        for (String placeholder : List.of("{subject}", "{grade}", "{imageSeq}", "{imageTotal}",
                "{retryHint}", "{knowledgePoints}")) {
            assertTrue(text.contains(placeholder), "缺占位符 " + placeholder);
        }
    }

    @Test
    @DisplayName("候选知识点区块 + 只能选清单内 ID 的规则 + 无法判断填空字符串")
    void should_describeKpSelectionRules() throws Exception {
        String text = prompt();
        assertTrue(text.contains("候选知识点"), "缺候选知识点小节");
        assertTrue(text.contains("knowledgePointId"), "缺逐题 kp 字段说明");
        assertTrue(text.contains("只能填清单中出现的 ID"), "缺白名单约束");
        assertTrue(text.contains("填空字符串"), "缺无法判断时的空串约定");
    }

    @Test
    @DisplayName("数学记号规则：允许 x^2 / a_n，禁止花括号上下标与反斜杠命令")
    void should_enforceMathNotationRule() throws Exception {
        String text = prompt();
        assertTrue(text.contains("x^2"), "应允许纯文本幂写法 x^2");
        assertTrue(text.contains("a_n"), "应允许纯文本下标写法 a_n");
        assertTrue(text.contains("禁止使用花括号形式 ^{...} 与 _{...}"), "应显式禁止花括号上下标");
        assertTrue(text.contains("反斜杠命令"), "应禁止 LaTeX 反斜杠命令");
        assertTrue(text.contains("√") && text.contains("≤"), "应给出可读符号写法");
        assertFalse(text.contains("{knowledgePoints} 占位符缺失"));
    }

    @Test
    @DisplayName("渲染后无残留占位符；空清单输出固定文案")
    void should_renderWithoutResidualPlaceholders() {
        ExamParseAgentImpl agent = new ExamParseAgentImpl();
        com.jiang.dto.ExamParseContext context = com.jiang.dto.ExamParseContext.builder()
                .subjectName("数学").gradeLabel("高一")
                .imageSeq(1).imageTotal(2).tenantId("default")
                .kpCandidates(List.of(new KpCandidate("kp-1", "集合与函数")))
                .build();
        String rendered = agent.buildPrompt(context, "无");

        assertFalse(rendered.matches("(?s).*\\{[a-zA-Z]+}.*"), "不得残留 {占位符}");
        assertTrue(rendered.contains("- kp-1 | 集合与函数"));

        context.setKpCandidates(List.of());
        assertTrue(agent.buildPrompt(context, "无").contains("（本卷无候选知识点）"));
    }
}
