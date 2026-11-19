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
@DisplayName("exam_parse_v2 Prompt 契约（v4 + 2026-11-12 详细解析/多选/配图）")
class ExamParsePromptContractTest {

    private String prompt() throws Exception {
        try (InputStream is = new ClassPathResource("prompts/versions/exam_parse_v2.txt").getInputStream()) {
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
    @DisplayName("v2 新增契约：详细解析 / 多选题 / 配图 figureRect / 单选多选区分")
    void should_describeV2Rules() throws Exception {
        String text = prompt();
        assertTrue(text.contains("详细"), "解析必须要求详细");
        assertTrue(text.contains("第 1 步"), "解析需分步");
        assertTrue(text.contains("多选"), "需说明多选题");
        assertTrue(text.contains("choiceMode"), "需输出 choiceMode（单选/多选区分）");
        assertTrue(text.contains("figureRect"), "需输出配图区域");
        assertTrue(text.contains("归一化坐标"), "配图坐标为相对图片的归一化坐标");
        assertTrue(text.contains("印刷"), "配图只框卷面印刷图形");
        assertTrue(text.contains("草稿"), "学生草稿不算配图");
        assertTrue(text.contains("不要把整段推导过程写进 correctAnswer")
                        || text.contains("只给结论式答案"), "correctAnswer 仍只放结论");
    }

    @Test
    @DisplayName("整卷 Prompt（paper v1）：跨页 / 答题卡 / 多选 / imageIndex")
    void should_describePaperPromptRules() throws Exception {
        String text;
        try (InputStream is = new ClassPathResource("prompts/versions/exam_parse_paper_v1.txt").getInputStream()) {
            text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(text.contains("{imageTotal}"), "缺图片总数占位符");
        assertTrue(text.contains("跨页"), "需说明跨页题合并");
        assertTrue(text.contains("答题卡"), "需说明从答题卡取答案");
        assertTrue(text.contains("imageIndex"), "需输出每题所在图片");
        assertTrue(text.contains("choiceMode"), "需输出 choiceMode");
        assertTrue(text.contains("{knowledgePoints}"), "需注入候选知识点");
        assertTrue(text.contains("{ocrText}"), "缺 OCR 文本占位符");
        assertTrue(text.contains("OCR"), "整卷 Prompt 需包含 OCR 文本使用规则");
        assertTrue(text.contains("表格"), "需说明表格内联进题干");
        assertTrue(text.contains("paperNo"), "需输出卷面题号 paperNo");
        assertTrue(text.contains("paperQuestionTotal"), "需输出整卷题数 paperQuestionTotal");
        assertTrue(text.contains("勾选"), "需说明选择题勾选即学生作答");
        assertTrue(text.contains("老师批改"), "需说明老师批改不得进入学生答案");
        assertTrue(text.contains("红笔"), "需说明红笔默认是老师批改（正确答案/解题过程不是学生答案）");
        assertTrue(text.contains("印刷内容"), "OCR 只作印刷内容真值");
        // 2026-11-17：答案-解析一致性（真实卷出现"标准答案 C / 解析写选 A"）
        assertTrue(text.contains("答案："), "解析最后一行需固定写成机器可读的 `答案：X`");
        assertTrue(text.contains("correctAnswer** 完全一致") || text.contains("完全一致"),
                "需要求结论行与 correctAnswer 一致");
    }

    @Test
    @DisplayName("双版本 Prompt（clean / answers）：只读印刷 / 只提作答 / 按 paperNo 对齐")
    void should_describeDualChannelPrompts() throws Exception {
        String clean;
        try (InputStream is = new ClassPathResource("prompts/versions/exam_parse_clean_v1.txt").getInputStream()) {
            clean = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(clean.contains("只识别"), "clean Prompt 需强调只识别");
        assertTrue(clean.contains("印刷内容"), "clean Prompt 需强调只读印刷");
        assertTrue(clean.contains("studentAnswer"), "clean Prompt 需输出 studentAnswer 空串");
        assertTrue(clean.contains("blank"), "clean Prompt 需固定 blank");
        // 2026-11-17 修复：clean 版必须继承整卷 Prompt 的强制规则，否则默认路径会退化
        assertTrue(clean.contains("多选"), "clean 版需保留多选判据");
        assertTrue(clean.contains("字母序"), "clean 版需保留多选答案按字母序连写");
        assertTrue(clean.contains("如图"), "clean 版需保留「如图必须给 figureRect」的强制项");
        assertTrue(clean.contains("封面"), "clean 版需说明封面/答题卡页不产生题目");
        assertTrue(clean.contains("编造"), "clean 版需禁止编造题目");
        assertTrue(clean.contains("unclearFields"), "clean 版需保留辨认不清字段的标注要求");
        assertTrue(clean.contains("第 1 步"), "clean 版需保留解析分步格式要求");

        String answers;
        try (InputStream is = new ClassPathResource("prompts/versions/exam_parse_answers_v1.txt").getInputStream()) {
            answers = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(answers.contains("红笔"), "answers Prompt 需说明红笔是老师批改");
        assertTrue(answers.contains("questionDigest"), "answers Prompt 需注入题目清单");
        assertTrue(answers.contains("paperNo"), "answers Prompt 需按 paperNo 对齐");
        assertTrue(answers.contains("blank"), "answers Prompt 需支持未作答");
        assertTrue(answers.contains("答题卡"), "answers Prompt 需覆盖答题卡作答（整卷 Prompt 第 5 条）");
        assertTrue(answers.contains("铅笔"), "answers Prompt 需覆盖铅笔/灰色笔作答");
        assertTrue(answers.contains("空数组"), "answers Prompt 需明确不能返回空数组");
    }

    @Test
    @DisplayName("2026-11-17：clean / answers Prompt 渲染后无残留占位符（占位符漂移可被发现）")
    void should_renderDualChannelPrompts_withoutResidualPlaceholders() {
        ExamParseAgentImpl agent = new ExamParseAgentImpl();
        com.jiang.dto.ExamParsePaperContext paperContext = com.jiang.dto.ExamParsePaperContext.builder()
                .subjectName("数学").gradeLabel("高三").imageTotal(4).tenantId("default")
                .ocrText("第1页 题干文本")
                .kpCandidates(List.of(new KpCandidate("kp-1", "集合与函数")))
                .build();
        for (String path : List.of(ExamParseAgentImpl.PAPER_PROMPT_PATH, ExamParseAgentImpl.CLEAN_PROMPT_PATH)) {
            String rendered = agent.buildWholePrompt(paperContext, "无", path);
            assertFalse(rendered.matches("(?s).*\\{[a-zA-Z]+}.*"),
                    path + " 渲染后残留未替换占位符");
            assertTrue(rendered.contains("数学"));
            assertTrue(rendered.contains("第1页 题干文本"));
            assertTrue(rendered.contains("- kp-1 | 集合与函数"));
        }

        String answersRendered = agent.buildAnswersPrompt(
                com.jiang.dto.ExamAnswerParseContext.builder()
                        .subjectName("数学").gradeLabel("高三").imageTotal(4).tenantId("default")
                        .questionDigest("- 第17题 | 题干：数列")
                        .build(), "无");
        assertFalse(answersRendered.matches("(?s).*\\{[a-zA-Z]+}.*"), "answers Prompt 渲染后残留未替换占位符");
        assertTrue(answersRendered.contains("第17题"));
        assertTrue(answersRendered.contains("高三"));
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
