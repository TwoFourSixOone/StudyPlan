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
}
