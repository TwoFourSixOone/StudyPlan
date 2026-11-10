package com.jiang.service;

import com.jiang.mapper.QuestionBankMapper;
import com.jiang.model.entity.QuestionBank;
import com.jiang.service.impl.BankQuestionPicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BankQuestionPicker 单元测试（产品规则 #3：题库优先）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankQuestionPicker 单元测试")
class BankQuestionPickerTest {

    @Mock
    private QuestionBankMapper questionBankMapper;

    @InjectMocks
    private BankQuestionPicker picker;

    private static final String KP_ID = "kp_001";
    private static final String TENANT_ID = "default";

    private QuestionBank buildQuestion(long id, Integer grade) {
        QuestionBank q = new QuestionBank();
        q.setQuestionId(id);
        q.setKnowledgePointId(KP_ID);
        q.setContent("题干" + id);
        q.setAnswer("A");
        q.setType("choice");
        q.setGrade(grade);
        return q;
    }

    @Test
    @DisplayName("按 questionId 升序取前 N 道")
    void should_pickFirstN_byQuestionId() {
        when(questionBankMapper.selectActiveByKp(KP_ID, TENANT_ID))
                .thenReturn(List.of(buildQuestion(3, null), buildQuestion(1, null), buildQuestion(2, null)));

        List<QuestionBank> picked = picker.pick(KP_ID, TENANT_ID, 2, 10, "10-12", Set.of());

        assertEquals(2, picked.size());
        assertEquals(1L, picked.get(0).getQuestionId());
        assertEquals(2L, picked.get(1).getQuestionId());
    }

    @Test
    @DisplayName("题目自带年级与不匹配学生年级 → 排除")
    void should_exclude_when_questionGradeMismatch() {
        when(questionBankMapper.selectActiveByKp(KP_ID, TENANT_ID))
                .thenReturn(List.of(buildQuestion(1, 8), buildQuestion(2, 10)));

        List<QuestionBank> picked = picker.pick(KP_ID, TENANT_ID, 5, 10, "7-9", Set.of());

        assertEquals(1, picked.size());
        assertEquals(2L, picked.get(0).getQuestionId());
    }

    @Test
    @DisplayName("已在排除集合中的题目不重复抽取")
    void should_exclude_alreadyUsedQuestions() {
        when(questionBankMapper.selectActiveByKp(KP_ID, TENANT_ID))
                .thenReturn(List.of(buildQuestion(1, null), buildQuestion(2, null)));

        List<QuestionBank> picked = picker.pick(KP_ID, TENANT_ID, 2, null, null, Set.of(1L));

        assertEquals(1, picked.size());
        assertEquals(2L, picked.get(0).getQuestionId());
    }

    @Test
    @DisplayName("可用题目不足时返回实际数量")
    void should_returnFewer_when_bankInsufficient() {
        when(questionBankMapper.selectActiveByKp(KP_ID, TENANT_ID))
                .thenReturn(List.of(buildQuestion(1, null)));

        List<QuestionBank> picked = picker.pick(KP_ID, TENANT_ID, 3, null, null, Set.of());

        assertEquals(1, picked.size());
    }

    @Test
    @DisplayName("题库查询异常 → 返回空列表（由 Agent 补足）")
    void should_returnEmpty_when_queryFails() {
        when(questionBankMapper.selectActiveByKp(KP_ID, TENANT_ID))
                .thenThrow(new RuntimeException("db error"));

        List<QuestionBank> picked = picker.pick(KP_ID, TENANT_ID, 2, null, null, Set.of());

        assertTrue(picked.isEmpty());
    }

    @Test
    @DisplayName("count <= 0 直接返回空，不查库")
    void should_skipQuery_when_countNotPositive() {
        assertTrue(picker.pick(KP_ID, TENANT_ID, 0, null, null, Set.of()).isEmpty());
        verifyNoInteractions(questionBankMapper);
    }
}
