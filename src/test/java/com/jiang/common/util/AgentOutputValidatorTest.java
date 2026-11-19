package com.jiang.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentOutputValidator 举一反三输出校验测试（隐性约定 #25）
 *
 * <p>重点：选择题缺选项必须判失败，否则会落库成「有题干、无选项」的死题。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("AgentOutputValidator 举一反三校验")
class AgentOutputValidatorTest {

    private final AgentOutputValidator validator = new AgentOutputValidator();

    @Test
    @DisplayName("选择题带 4 个对象选项 → 通过")
    void should_pass_choice_with_objects() {
        String output = """
                [{
                  "knowledgePointId": "kp_001",
                  "content": {
                    "stem": "4 和 2 比，哪个数大？",
                    "choices": [
                      {"label": "A", "text": "4"},
                      {"label": "B", "text": "2"},
                      {"label": "C", "text": "一样大"},
                      {"label": "D", "text": "无法比较"}
                    ],
                    "answer": "A",
                    "analysis": "4 比 2 大",
                    "formatType": "choice",
                    "difficulty": "basic"
                  }
                }]
                """;

        assertTrue(validator.validateSimilar(output).isSuccess());
    }

    @Test
    @DisplayName("选择题缺 choices → 失败并提示选项不足")
    void should_fail_choice_without_choices() {
        String output = """
                [{
                  "knowledgePointId": "kp_001",
                  "content": {
                    "stem": "一个质点依次经过点 A(1,2)、B(3,5)，则该向量的模为（ ）",
                    "answer": "5",
                    "analysis": "略",
                    "formatType": "choice",
                    "difficulty": "standard"
                  }
                }]
                """;

        AgentOutputValidator.ValidationResult result = validator.validateSimilar(output);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("至少需要"), result.getMessage());
    }

    @Test
    @DisplayName("选择题只有 1 个选项 → 失败")
    void should_fail_choice_with_single_option() {
        String output = """
                [{
                  "knowledgePointId": "kp_001",
                  "content": {
                    "stem": "题干",
                    "choices": ["A. 只有一个"],
                    "answer": "A",
                    "formatType": "choice"
                  }
                }]
                """;

        assertFalse(validator.validateSimilar(output).isSuccess());
    }

    @Test
    @DisplayName("选择题选项为字符串数组（非标准形态）→ 兼容通过")
    void should_pass_choice_with_string_options() {
        String output = """
                [{
                  "knowledgePointId": "kp_001",
                  "content": {
                    "stem": "题干",
                    "choices": ["A. 1", "B. 2", "C. 3"],
                    "answer": "B",
                    "formatType": "choice"
                  }
                }]
                """;

        assertTrue(validator.validateSimilar(output).isSuccess());
    }

    @Test
    @DisplayName("非选择题无 choices → 通过")
    void should_pass_non_choice_without_choices() {
        String output = """
                [{
                  "knowledgePointId": "kp_001",
                  "content": {
                    "stem": "求 x",
                    "answer": "1",
                    "formatType": "numeric"
                  }
                }]
                """;

        assertTrue(validator.validateSimilar(output).isSuccess());
    }

    @Test
    @DisplayName("非 JSON 数组 → 失败")
    void should_fail_non_array() {
        assertFalse(validator.validateSimilar("{\"foo\":1}").isSuccess());
        assertFalse(validator.validateSimilar("not json").isSuccess());
    }

    // ==================== 出题 Agent（知识回顾/综合）主路径 ====================

    @Test
    @DisplayName("生成的选择题缺 choices → 主路径校验失败")
    void should_fail_generated_choice_without_choices() {
        String output = """
                [{
                  "questionId": 0,
                  "questionSource": "generated",
                  "taskType": "review",
                  "knowledgePointId": "kp_001",
                  "content": {
                    "stem": "题干",
                    "answer": "5",
                    "formatType": "choice",
                    "difficulty": "standard"
                  }
                }]
                """;

        AgentOutputValidator.ValidationResult result = validator.validate(output);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("至少需要"), result.getMessage());
    }

    @Test
    @DisplayName("题库题（questionSource=bank）不校验选项 → 通过")
    void should_pass_bank_choice_without_choices() {
        String output = """
                [{
                  "questionId": 1001,
                  "questionSource": "bank",
                  "taskType": "redo",
                  "knowledgePointId": "kp_001",
                  "content": {
                    "stem": "题干",
                    "answer": "A",
                    "formatType": "choice",
                    "difficulty": "standard"
                  }
                }]
                """;

        assertTrue(validator.validate(output).isSuccess());
    }

    // ==================== 试卷视觉解析（exam_parse_v1） ====================

    @Test
    @DisplayName("试卷解析：合法单题 → 通过")
    void should_pass_examParse_when_itemValid() {
        String output = """
                {"questions":[{
                  "questionIndex": 1,
                  "stem": "下列各数中，最小的数是（ ）",
                  "formatType": "choice",
                  "choices": [{"label":"A","text":"-3"},{"label":"B","text":"0"}],
                  "correctAnswer": "A",
                  "studentAnswer": "B",
                  "answerStatus": "answered",
              "knowledgePointId": "kp-1",
                  "difficulty": "basic",
                  "explanation": "负数最小"
                }]}
                """;

        assertTrue(validator.validateExamParse(output).isSuccess());
    }

    @Test
    @DisplayName("试卷解析：缺少 questions → 整图失败")
    void should_fail_examParse_when_questionsMissing() {
        assertFalse(validator.validateExamParse("{\"imageWarning\":\"封面\"}").isSuccess());
        assertFalse(validator.validateExamParse("[]").isSuccess());
        assertFalse(validator.validateExamParse("not json").isSuccess());
    }

    @Test
    @DisplayName("试卷解析：单题缺 stem/formatType 越界 → 无合法题 → 整图失败")
    void should_fail_examParse_when_noValidItem() {
        String missingStem = """
                {"questions":[{"questionIndex":1,"formatType":"short","studentAnswer":"","answerStatus":"blank"}]}
                """;
        assertFalse(validator.validateExamParse(missingStem).isSuccess());

        String badFormatType = """
                {"questions":[{"questionIndex":1,"stem":"题干","formatType":"essay","studentAnswer":"","answerStatus":"blank"}]}
                """;
        assertFalse(validator.validateExamParse(badFormatType).isSuccess());
    }

    @Test
    @DisplayName("试卷解析：选择题选项不足 2 个 → 该题非法 → 整图失败")
    void should_fail_examParse_when_choiceOptionsNotUsable() {
        String output = """
                {"questions":[{
                  "questionIndex": 1,
                  "stem": "题干",
                  "formatType": "choice",
                  "choices": [{"label":"A","text":"4"}],
                  "correctAnswer": "A",
                  "studentAnswer": "A",
                  "answerStatus": "answered",
                  "knowledgePointId": ""
                }]}
                """;

        AgentOutputValidator.ValidationResult result = validator.validateExamParse(output);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("至少需要"), result.getMessage());
    }

    @Test
    @DisplayName("试卷解析：空数组 + imageWarning（封面页）→ 合法 0 题")
    void should_pass_examParse_when_emptyQuestionsWithWarning() {
        assertTrue(validator.validateExamParse(
                "{\"questions\":[],\"imageWarning\":\"本页为试卷封面\"}").isSuccess());
    }

    @Test
    @DisplayName("试卷解析：一题合法一题非法 → 整图通过（非法题由调用方丢弃）")
    void should_pass_examParse_when_partiallyValid() throws Exception {
        String output = """
                {"questions":[
                  {"questionIndex":1,"stem":"题干一","formatType":"short","studentAnswer":"答案","answerStatus":"answered","knowledgePointId":"kp-1"},
                  {"questionIndex":2,"stem":"题干二","formatType":"essay","studentAnswer":"","answerStatus":"blank"}
                ]}
                """;

        assertTrue(validator.validateExamParse(output).isSuccess());

        com.fasterxml.jackson.databind.JsonNode invalidItem = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"questionIndex\":2,\"stem\":\"题干二\",\"formatType\":\"essay\","
                        + "\"studentAnswer\":\"\",\"answerStatus\":\"blank\",\"knowledgePointId\":\"\"}");
        String error = validator.checkExamParseItem(invalidItem);
        assertNotNull(error);
        assertTrue(error.contains("formatType"), error);
    }

    @Test
    @DisplayName("exam_parse_schema.json 可解析，关键约束与 design §3.3 一致")
    void should_loadExamParseSchema() throws Exception {
        try (java.io.InputStream is = new org.springframework.core.io.ClassPathResource(
                "prompts/schemas/exam_parse_schema.json").getInputStream()) {
            com.fasterxml.jackson.databind.JsonNode schema =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(is);
            assertEquals("object", schema.get("type").asText());
            assertTrue(schema.get("required").toString().contains("questions"));
            assertEquals(0, schema.at("/properties/questions/minItems").asInt());
            assertEquals(60, schema.at("/properties/questions/maxItems").asInt());

            com.fasterxml.jackson.databind.JsonNode item = schema.at("/properties/questions/items");
            String required = item.get("required").toString();
            assertTrue(required.contains("stem"));
            assertTrue(required.contains("formatType"));
            assertTrue(required.contains("studentAnswer"));
            assertTrue(required.contains("answerStatus"));
            // 2026-11-17：questionIndex 不再必填（服务端以 paperNo 重新编号），
            // 但题号至少给一个 —— schema 用 anyOf(questionIndex | paperNo) 表达，与校验器一致
            assertFalse(required.contains("questionIndex"), "questionIndex 已移出 required: " + required);
            String anyOf = item.get("anyOf").toString();
            assertTrue(anyOf.contains("questionIndex"), anyOf);
            assertTrue(anyOf.contains("paperNo"), anyOf);
            assertEquals(8, item.at("/properties/choices/maxItems").asInt());
            assertTrue(item.at("/properties/formatType/enum").toString().contains("choice"));
            assertTrue(item.at("/properties/answerStatus/enum").toString().contains("unreadable"));
            assertTrue(item.at("/properties/difficulty/enum").toString().contains("challenge"));
            assertEquals(200, schema.at("/properties/imageWarning/maxLength").asInt());
            assertEquals(60, schema.at("/properties/paperQuestionTotal/maximum").asInt());
            assertEquals(60, item.at("/properties/paperNo/maximum").asInt());
        }
    }

    @Test
    @DisplayName("试卷解析：explanation 1200 字通过、1600 字拒绝（上限与 schema 1500 对齐）")
    void should_allowLongerExplanation() {
        String template = "{\"questions\":[{\"questionIndex\":1,\"stem\":\"题干\",\"formatType\":\"short\","
                + "\"studentAnswer\":\"\",\"answerStatus\":\"blank\",\"knowledgePointId\":\"\","
                + "\"explanation\":\"%s\"}]}";
        assertTrue(validator.validateExamParse(String.format(template, "解".repeat(1200))).isSuccess());
        assertFalse(validator.validateExamParse(String.format(template, "解".repeat(1600))).isSuccess());
    }

    @Test
    @DisplayName("作答版（2026-11-17）：合法通过；缺字段/paperNo 越界/status 非法 → 失败")
    void should_validateExamAnswers() {
        String valid = "{\"answers\":[{\"paperNo\":1,\"studentAnswer\":\"C\","
                + "\"answerStatus\":\"answered\"}]}";
        assertTrue(validator.validateExamAnswers(valid).isSuccess());

        assertFalse(validator.validateExamAnswers(
                "{\"answers\":[{\"paperNo\":1,\"studentAnswer\":\"C\"}]}").isSuccess());
        assertFalse(validator.validateExamAnswers(
                "{\"answers\":[{\"paperNo\":0,\"studentAnswer\":\"\","
                        + "\"answerStatus\":\"blank\"}]}").isSuccess());
        assertFalse(validator.validateExamAnswers(
                "{\"answers\":[{\"paperNo\":1,\"studentAnswer\":\"\","
                        + "\"answerStatus\":\"maybe\"}]}").isSuccess());
        assertFalse(validator.validateExamAnswers("{\"answers\":\"x\"}").isSuccess());
    }

    @Test
    @DisplayName("作答版：answers 为空数组 → 失败（题目清单必须逐题输出，否则整卷会被判 0 分）")
    void should_failExamAnswers_when_answersEmpty() {
        assertFalse(validator.validateExamAnswers("{\"answers\":[]}").isSuccess());
        assertFalse(validator.validateExamAnswers("{\"answers\":[],\"imageWarning\":\"无法读取\"}").isSuccess());
    }

    @Test
    @DisplayName("作答版：unclearFields 超过 6 项 → 该条非法（与 schema maxItems 对齐）")
    void should_rejectAnswerItem_when_unclearFieldsTooMany() throws Exception {
        String item = "{\"paperNo\":1,\"studentAnswer\":\"C\",\"answerStatus\":\"answered\","
                + "\"unclearFields\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}";
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(item);
        String error = validator.checkExamAnswerItem(node);
        assertNotNull(error);
        assertTrue(error.contains("unclearFields"), error);

        // 6 项以内合法
        assertNull(validator.checkExamAnswerItem(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"paperNo\":1,\"studentAnswer\":\"C\",\"answerStatus\":\"answered\","
                        + "\"unclearFields\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}")));
    }

    @Test
    @DisplayName("题目：questionIndex 缺失但 paperNo 存在 → 合法（不因缺 questionIndex 静默丢题）")
    void should_passExamParseItem_when_onlyPaperNoPresent() throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"paperNo\":17,\"stem\":\"题干\",\"formatType\":\"short\",\"studentAnswer\":\"\","
                        + "\"answerStatus\":\"blank\",\"knowledgePointId\":\"\"}");
        assertNull(validator.checkExamParseItem(node));

        // 两者都缺 → 仍非法
        com.fasterxml.jackson.databind.JsonNode noNo = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"stem\":\"题干\",\"formatType\":\"short\",\"studentAnswer\":\"\","
                        + "\"answerStatus\":\"blank\",\"knowledgePointId\":\"\"}");
        String error = validator.checkExamParseItem(noNo);
        assertNotNull(error);
        assertTrue(error.contains("题号"), error);
    }
}

