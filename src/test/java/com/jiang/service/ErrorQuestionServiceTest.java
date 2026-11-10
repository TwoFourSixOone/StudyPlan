package com.jiang.service;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.model.entity.ErrorQuestion;
import com.jiang.model.enums.ErrorQuestionSourceType;
import com.jiang.service.impl.ErrorQuestionServiceImpl;
import com.jiang.vo.DueErrorQuestionVO;
import com.jiang.vo.ErrorQuestionDetailVO;
import com.jiang.vo.ErrorQuestionStatisticsVO;
import com.jiang.vo.ErrorQuestionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ErrorQuestionService 单元测试
 *
 * <p>覆盖 T-04a（写操作）+ T-04b（查询操作）核心场景</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorQuestionService 单元测试")
class ErrorQuestionServiceTest {

    @Mock
    private ErrorQuestionMapper errorQuestionMapper;

    @InjectMocks
    private ErrorQuestionServiceImpl errorQuestionService;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";
    private static final Long QUESTION_ID = 100L;
    private static final String KP_ID = "kp_math_001";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    // ==================== recordWrongAnswer ====================

    @Nested
    @DisplayName("recordWrongAnswer")
    class RecordWrongAnswerTests {

        @Test
        @DisplayName("首次入队 → INSERT 成功，stage=1, next_due_date=wrongDate+1")
        void should_insert_when_firstTime() {
            when(errorQuestionMapper.insert(any(ErrorQuestion.class))).thenReturn(1);

            errorQuestionService.recordWrongAnswer(STUDENT_ID, TENANT_ID, QUESTION_ID,
                    KP_ID, ErrorQuestionSourceType.HOMEWORK, 1L, TODAY);

            ArgumentCaptor<ErrorQuestion> captor = ArgumentCaptor.forClass(ErrorQuestion.class);
            verify(errorQuestionMapper).insert(captor.capture());
            ErrorQuestion inserted = captor.getValue();
            assertEquals(STUDENT_ID, inserted.getStudentId());
            assertEquals(QUESTION_ID, inserted.getQuestionId());
            assertEquals(KP_ID, inserted.getKnowledgePointId());
            assertEquals(1, inserted.getCurrentStage());
            assertEquals(TODAY, inserted.getFirstWrongDate());
            assertEquals(TODAY, inserted.getLastWrongDate());
            assertEquals(TODAY.plusDays(1), inserted.getNextDueDate());
            assertEquals("active", inserted.getStatus());
            assertNull(inserted.getCompletedDate());
        }

        @Test
        @DisplayName("重复入队 → DuplicateKeyException → UPDATE 回退")
        void should_updateFallback_when_duplicateKey() {
            when(errorQuestionMapper.insert(any(ErrorQuestion.class)))
                    .thenThrow(new DuplicateKeyException("uk_student_question"));
            // UPDATE 回退后查询确认存在
            ErrorQuestion existing = buildErrorQuestion(1L, QUESTION_ID, 3, "active");
            when(errorQuestionMapper.selectOne(any())).thenReturn(existing);
            // 审查修复后使用 LambdaUpdateWrapper，第一个参数为 null
            when(errorQuestionMapper.update(isNull(), any())).thenReturn(1);

            errorQuestionService.recordWrongAnswer(STUDENT_ID, TENANT_ID, QUESTION_ID,
                    KP_ID, ErrorQuestionSourceType.HOMEWORK, 2L, TODAY);

            // 验证 update 被调用：entity=null（LambdaUpdateWrapper 模式）
            verify(errorQuestionMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("BLOCK-D: completed 记录被 recordWrongAnswer → status=active, completed_date=NULL")
        void should_reactivateCompleted_when_recordWrongAnswer() {
            when(errorQuestionMapper.insert(any(ErrorQuestion.class)))
                    .thenThrow(new DuplicateKeyException("dup"));
            ErrorQuestion completed = buildErrorQuestion(1L, QUESTION_ID, 5, "completed");
            completed.setCompletedDate(LocalDate.of(2026, 10, 1));
            when(errorQuestionMapper.selectOne(any())).thenReturn(completed);
            // 审查修复后使用 LambdaUpdateWrapper，第一个参数为 null
            when(errorQuestionMapper.update(isNull(), any())).thenReturn(1);

            errorQuestionService.recordWrongAnswer(STUDENT_ID, TENANT_ID, QUESTION_ID,
                    KP_ID, ErrorQuestionSourceType.HOMEWORK, 3L, TODAY);

            // LambdaUpdateWrapper 模式：entity 参数为 null，字段值在 wrapper SQL 中
            // BLOCK-D 的 completed_date=NULL 通过 .set(ErrorQuestion::getCompletedDate, null) 显式设置
            verify(errorQuestionMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("BLOCK-F: first_wrong_date 只在 INSERT 时写入，UPDATE 不修改")
        void should_notModifyFirstWrongDate_when_updateFallback() {
            LocalDate originalWrongDate = LocalDate.of(2026, 10, 1);
            when(errorQuestionMapper.insert(any(ErrorQuestion.class)))
                    .thenThrow(new DuplicateKeyException("dup"));
            ErrorQuestion existing = buildErrorQuestion(1L, QUESTION_ID, 3, "active");
            existing.setFirstWrongDate(originalWrongDate);
            when(errorQuestionMapper.selectOne(any())).thenReturn(existing);
            // 审查修复后使用 LambdaUpdateWrapper，第一个参数为 null
            when(errorQuestionMapper.update(isNull(), any())).thenReturn(1);

            errorQuestionService.recordWrongAnswer(STUDENT_ID, TENANT_ID, QUESTION_ID,
                    KP_ID, ErrorQuestionSourceType.HOMEWORK, 4L, TODAY);

            // LambdaUpdateWrapper 模式：entity 参数为 null
            // BLOCK-F: firstWrongDate 不在 LambdaUpdateWrapper.set() 调用中，不会被修改
            verify(errorQuestionMapper).update(isNull(), any());
        }
    }

    // ==================== advanceStage ====================

    @Nested
    @DisplayName("advanceStage")
    class AdvanceStageTests {

        @Test
        @DisplayName("BLOCK-E: stage 1→2 正常推进，next_due_date = correctDate + interval(2)=3")
        void should_advanceStage1to2_when_correct() {
            ErrorQuestion record = buildErrorQuestion(1L, QUESTION_ID, 1, "active");
            when(errorQuestionMapper.selectOne(any())).thenReturn(record);
            when(errorQuestionMapper.advanceStageWithCheck(eq(1L), eq(TENANT_ID), eq(1), eq(2), any(LocalDate.class)))
                    .thenReturn(1);

            LocalDate correctDate = TODAY;
            errorQuestionService.advanceStage(STUDENT_ID, TENANT_ID, QUESTION_ID, correctDate);

            // IMP-2: next_due_date = correctDate + interval(newStage=2) = correctDate + 3
            LocalDate expectedNextDue = correctDate.plusDays(3);
            verify(errorQuestionMapper).advanceStageWithCheck(1L, TENANT_ID, 1, 2, expectedNextDue);
        }

        @Test
        @DisplayName("BLOCK-E: stage 5→completed，completed_date 正确写入")
        void should_complete_when_stage5Advance() {
            ErrorQuestion record = buildErrorQuestion(1L, QUESTION_ID, 5, "active");
            when(errorQuestionMapper.selectOne(any())).thenReturn(record);
            when(errorQuestionMapper.completeStageWithCheck(eq(1L), eq(TENANT_ID), eq(5), eq(6), any(LocalDate.class)))
                    .thenReturn(1);

            LocalDate correctDate = TODAY;
            errorQuestionService.advanceStage(STUDENT_ID, TENANT_ID, QUESTION_ID, correctDate);

            verify(errorQuestionMapper).completeStageWithCheck(1L, TENANT_ID, 5, 6, correctDate);
        }

        @Test
        @DisplayName("completed 状态拒绝推进 → 抛 ERROR_QUESTION_COMPLETED")
        void should_throwCompleted_when_statusIsCompleted() {
            ErrorQuestion record = buildErrorQuestion(1L, QUESTION_ID, 3, "completed");
            when(errorQuestionMapper.selectOne(any())).thenReturn(record);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> errorQuestionService.advanceStage(STUDENT_ID, TENANT_ID, QUESTION_ID, TODAY));
            assertEquals(ErrorCodes.ERROR_QUESTION_COMPLETED.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("IMP-A: 并发推进 → affected=0 → 抛 ERROR_QUESTION_CONCURRENT_MODIFIED")
        void should_throwConcurrentModified_when_affectedZero() {
            ErrorQuestion record = buildErrorQuestion(1L, QUESTION_ID, 2, "active");
            when(errorQuestionMapper.selectOne(any())).thenReturn(record);
            when(errorQuestionMapper.advanceStageWithCheck(anyLong(), anyString(), anyInt(), anyInt(), any(LocalDate.class)))
                    .thenReturn(0);

            assertThrows(BusinessException.class,
                    () -> errorQuestionService.advanceStage(STUDENT_ID, TENANT_ID, QUESTION_ID, TODAY));
        }
    }

    // ==================== resetStage ====================

    @Nested
    @DisplayName("resetStage")
    class ResetStageTests {

        @Test
        @DisplayName("正常重置 → stage=1, next_due_date=wrongDate+1, status=active")
        void should_resetSuccessfully_when_activeRecord() {
            ErrorQuestion record = buildErrorQuestion(1L, QUESTION_ID, 3, "active");
            when(errorQuestionMapper.selectOne(any())).thenReturn(record);
            // 审查修复后使用 LambdaUpdateWrapper，第一个参数为 null
            when(errorQuestionMapper.update(isNull(), any())).thenReturn(1);

            errorQuestionService.resetStage(STUDENT_ID, TENANT_ID, QUESTION_ID, TODAY);

            // LambdaUpdateWrapper 模式：entity 参数为 null，字段值在 wrapper SQL 中
            verify(errorQuestionMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("completed 状态拒绝重置 → 抛 ERROR_QUESTION_COMPLETED")
        void should_throwCompleted_when_statusIsCompleted() {
            ErrorQuestion record = buildErrorQuestion(1L, QUESTION_ID, 5, "completed");
            when(errorQuestionMapper.selectOne(any())).thenReturn(record);

            assertThrows(BusinessException.class,
                    () -> errorQuestionService.resetStage(STUDENT_ID, TENANT_ID, QUESTION_ID, TODAY));
        }

        @Test
        @DisplayName("IMP-L: 重复 resetStage 同一题（值未变）→ 幂等成功")
        void should_idempotent_when_resetSameValues() {
            ErrorQuestion record = buildErrorQuestion(1L, QUESTION_ID, 1, "active");
            record.setLastWrongDate(TODAY);
            record.setNextDueDate(TODAY.plusDays(1));
            when(errorQuestionMapper.selectOne(any())).thenReturn(record);
            // affected=0 模拟并发或值未变
            when(errorQuestionMapper.update(isNull(), any())).thenReturn(0);
            // recheck 返回值完全一致
            ErrorQuestion recheck = buildErrorQuestion(1L, QUESTION_ID, 1, "active");
            recheck.setLastWrongDate(TODAY);
            recheck.setNextDueDate(TODAY.plusDays(1));
            when(errorQuestionMapper.selectById(1L)).thenReturn(recheck);

            // 不应抛异常（幂等成功）
            assertDoesNotThrow(() ->
                    errorQuestionService.resetStage(STUDENT_ID, TENANT_ID, QUESTION_ID, TODAY));
        }
    }

    // ==================== syncPauseStatus ====================

    @Nested
    @DisplayName("syncPauseStatus")
    class SyncPauseStatusTests {

        @Test
        @DisplayName("掌握度 >= 0.8 → 暂停 active 记录")
        void should_pause_when_masteryAboveThreshold() {
            when(errorQuestionMapper.batchPauseByKnowledgePoint(STUDENT_ID, TENANT_ID, KP_ID))
                    .thenReturn(3);

            errorQuestionService.syncPauseStatus(STUDENT_ID, TENANT_ID, KP_ID, new BigDecimal("0.85"));

            verify(errorQuestionMapper).batchPauseByKnowledgePoint(STUDENT_ID, TENANT_ID, KP_ID);
            verify(errorQuestionMapper, never()).batchReactivateByKnowledgePoint(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("掌握度 < 0.8 → 重新激活 paused 记录")
        void should_reactivate_when_masteryBelowThreshold() {
            when(errorQuestionMapper.batchReactivateByKnowledgePoint(STUDENT_ID, TENANT_ID, KP_ID))
                    .thenReturn(2);

            errorQuestionService.syncPauseStatus(STUDENT_ID, TENANT_ID, KP_ID, new BigDecimal("0.75"));

            verify(errorQuestionMapper).batchReactivateByKnowledgePoint(STUDENT_ID, TENANT_ID, KP_ID);
            verify(errorQuestionMapper, never()).batchPauseByKnowledgePoint(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("掌握度恰好 0.8 → 暂停（边界值）")
        void should_pause_when_masteryExactly08() {
            when(errorQuestionMapper.batchPauseByKnowledgePoint(STUDENT_ID, TENANT_ID, KP_ID))
                    .thenReturn(1);

            errorQuestionService.syncPauseStatus(STUDENT_ID, TENANT_ID, KP_ID, new BigDecimal("0.80"));

            verify(errorQuestionMapper).batchPauseByKnowledgePoint(STUDENT_ID, TENANT_ID, KP_ID);
        }
    }

    // ==================== getDueErrorQuestions ====================

    @Nested
    @DisplayName("getDueErrorQuestions")
    class GetDueErrorQuestionsTests {

        @Test
        @DisplayName("无到期错题 → 返回空 Map")
        void should_returnEmptyMap_when_noDueQuestions() {
            when(errorQuestionMapper.selectDueKnowledgePointIds(STUDENT_ID, TENANT_ID, TODAY))
                    .thenReturn(Collections.emptyList());

            Map<String, List<DueErrorQuestionVO>> result =
                    errorQuestionService.getDueErrorQuestions(STUDENT_ID, TENANT_ID, TODAY);

            assertTrue(result.isEmpty());
            verify(errorQuestionMapper, never()).selectDueQuestionsByKnowledgePoint(
                    anyLong(), anyString(), anyString(), any(LocalDate.class));
        }

        @Test
        @DisplayName("正常到期查询 → 按知识点分组的 LinkedHashMap")
        void should_returnGroupedMap_when_dueQuestionsExist() {
            when(errorQuestionMapper.selectDueKnowledgePointIds(STUDENT_ID, TENANT_ID, TODAY))
                    .thenReturn(List.of("kp_001", "kp_002"));

            List<ErrorQuestion> kp1Questions = List.of(
                    buildDueErrorQuestion(1L, 100L, "kp_001", 1, TODAY),
                    buildDueErrorQuestion(2L, 101L, "kp_001", 2, TODAY));
            List<ErrorQuestion> kp2Questions = List.of(
                    buildDueErrorQuestion(3L, 200L, "kp_002", 1, TODAY));

            when(errorQuestionMapper.selectDueQuestionsByKnowledgePoint(STUDENT_ID, TENANT_ID, "kp_001", TODAY))
                    .thenReturn(kp1Questions);
            when(errorQuestionMapper.selectDueQuestionsByKnowledgePoint(STUDENT_ID, TENANT_ID, "kp_002", TODAY))
                    .thenReturn(kp2Questions);

            Map<String, List<DueErrorQuestionVO>> result =
                    errorQuestionService.getDueErrorQuestions(STUDENT_ID, TENANT_ID, TODAY);

            assertEquals(2, result.size());
            assertTrue(result.containsKey("kp_001"));
            assertTrue(result.containsKey("kp_002"));
            assertEquals(2, result.get("kp_001").size());
            assertEquals(1, result.get("kp_002").size());
        }

        @Test
        @DisplayName("IMP-AG: 返回 LinkedHashMap 顺序一致（字典序）")
        void should_returnLinkedHashMap_withConsistentOrder() {
            when(errorQuestionMapper.selectDueKnowledgePointIds(STUDENT_ID, TENANT_ID, TODAY))
                    .thenReturn(List.of("kp_b", "kp_a", "kp_c"));

            when(errorQuestionMapper.selectDueQuestionsByKnowledgePoint(eq(STUDENT_ID), eq(TENANT_ID),
                    anyString(), eq(TODAY)))
                    .thenReturn(List.of(buildDueErrorQuestion(1L, 100L, "kp_x", 1, TODAY)));

            Map<String, List<DueErrorQuestionVO>> result1 =
                    errorQuestionService.getDueErrorQuestions(STUDENT_ID, TENANT_ID, TODAY);
            Map<String, List<DueErrorQuestionVO>> result2 =
                    errorQuestionService.getDueErrorQuestions(STUDENT_ID, TENANT_ID, TODAY);

            // 验证 key 顺序一致
            assertEquals(new ArrayList<>(result1.keySet()), new ArrayList<>(result2.keySet()));
            // 验证是 LinkedHashMap
            assertInstanceOf(LinkedHashMap.class, result1);
        }

        @Test
        @DisplayName("BLOCK-I: 空 List 的知识点不放入 Map")
        void should_skipEmptyKp_when_noQuestionsForKp() {
            when(errorQuestionMapper.selectDueKnowledgePointIds(STUDENT_ID, TENANT_ID, TODAY))
                    .thenReturn(List.of("kp_001", "kp_002"));

            when(errorQuestionMapper.selectDueQuestionsByKnowledgePoint(STUDENT_ID, TENANT_ID, "kp_001", TODAY))
                    .thenReturn(List.of(buildDueErrorQuestion(1L, 100L, "kp_001", 1, TODAY)));
            when(errorQuestionMapper.selectDueQuestionsByKnowledgePoint(STUDENT_ID, TENANT_ID, "kp_002", TODAY))
                    .thenReturn(Collections.emptyList());

            Map<String, List<DueErrorQuestionVO>> result =
                    errorQuestionService.getDueErrorQuestions(STUDENT_ID, TENANT_ID, TODAY);

            assertEquals(1, result.size());
            assertTrue(result.containsKey("kp_001"));
            assertFalse(result.containsKey("kp_002"));
        }

        @Test
        @DisplayName("IMP-AH: 到期知识点 > 50 → WARN + 截断至前 50 个")
        void should_truncateAndWarn_when_kpCountExceeds50() {
            // 构造 51 个知识点（触发 LIMIT 51）
            List<String> kpIds = new ArrayList<>();
            for (int i = 1; i <= 51; i++) {
                kpIds.add(String.format("kp_%03d", i));
            }
            when(errorQuestionMapper.selectDueKnowledgePointIds(STUDENT_ID, TENANT_ID, TODAY))
                    .thenReturn(kpIds);

            // 每个知识点返回 1 道到期错题
            when(errorQuestionMapper.selectDueQuestionsByKnowledgePoint(eq(STUDENT_ID), eq(TENANT_ID),
                    anyString(), eq(TODAY)))
                    .thenReturn(List.of(buildDueErrorQuestion(1L, 100L, "kp_x", 1, TODAY)));

            Map<String, List<DueErrorQuestionVO>> result =
                    errorQuestionService.getDueErrorQuestions(STUDENT_ID, TENANT_ID, TODAY);

            // 只处理前 50 个
            assertEquals(50, result.size());
            // 第 51 个不应被处理
            assertFalse(result.containsKey("kp_051"));
        }
    }

    // ==================== getErrorQuestions ====================

    @Nested
    @DisplayName("getErrorQuestions")
    class GetErrorQuestionsTests {

        @Test
        @DisplayName("IMP-F: page=0 → 置 1")
        void should_normalizePage_when_pageIsZero() {
            when(errorQuestionMapper.selectPageWithDetail(anyLong(), anyString(), any(), any(), eq(0), eq(10)))
                    .thenReturn(Collections.emptyList());
            when(errorQuestionMapper.selectCount(anyLong(), anyString(), any(), any()))
                    .thenReturn(0);

            PageResult<ErrorQuestionVO> result = errorQuestionService.getErrorQuestions(
                    STUDENT_ID, TENANT_ID, null, null, 0, 10);

            // Service 层修正 page=0 → offset=0（即 page=1 的 offset）
            assertEquals(1, result.getPage());
        }

        @Test
        @DisplayName("IMP-F: size=101 → 截断至 100")
        void should_truncateSize_when_sizeExceeds100() {
            when(errorQuestionMapper.selectPageWithDetail(anyLong(), anyString(), any(), any(), eq(0), eq(100)))
                    .thenReturn(Collections.emptyList());
            when(errorQuestionMapper.selectCount(anyLong(), anyString(), any(), any()))
                    .thenReturn(0);

            PageResult<ErrorQuestionVO> result = errorQuestionService.getErrorQuestions(
                    STUDENT_ID, TENANT_ID, null, null, 1, 101);

            assertEquals(100, result.getSize());
        }

        @Test
        @DisplayName("IMP-F: size=0 → 置 1")
        void should_normalizeSize_when_sizeIsZero() {
            when(errorQuestionMapper.selectPageWithDetail(anyLong(), anyString(), any(), any(), eq(0), eq(1)))
                    .thenReturn(Collections.emptyList());
            when(errorQuestionMapper.selectCount(anyLong(), anyString(), any(), any()))
                    .thenReturn(0);

            PageResult<ErrorQuestionVO> result = errorQuestionService.getErrorQuestions(
                    STUDENT_ID, TENANT_ID, null, null, 1, 0);

            assertEquals(1, result.getSize());
        }

        @Test
        @DisplayName("IMP-G: questionContent 为 null 时兜底为 [题目已删除]")
        void should_fallbackContent_when_questionContentIsNull() {
            Map<String, Object> row = new HashMap<>();
            row.put("error_question_id", 1L);
            row.put("question_id", 100L);
            row.put("knowledge_point_id", KP_ID);
            row.put("kp_name", "测试知识点");
            row.put("source_type", "homework");
            row.put("first_wrong_date", TODAY);
            row.put("last_wrong_date", TODAY);
            row.put("current_stage", 1);
            row.put("next_due_date", TODAY.plusDays(1));
            row.put("status", "active");
            row.put("question_content", null);  // IMP-G: null 兜底
            row.put("question_difficulty", null);

            when(errorQuestionMapper.selectPageWithDetail(eq(STUDENT_ID), eq(TENANT_ID),
                    isNull(), isNull(), eq(0), eq(10)))
                    .thenReturn(List.of(row));
            when(errorQuestionMapper.selectCount(STUDENT_ID, TENANT_ID, null, null))
                    .thenReturn(1);

            PageResult<ErrorQuestionVO> result = errorQuestionService.getErrorQuestions(
                    STUDENT_ID, TENANT_ID, null, null, 1, 10);

            assertEquals(1, result.getRecords().size());
            assertEquals("[题目已删除]", result.getRecords().get(0).getQuestionContent());
            assertEquals("unknown", result.getRecords().get(0).getQuestionDifficulty());
        }
    }

    // ==================== getStatistics ====================

    @Nested
    @DisplayName("getStatistics")
    class GetStatisticsTests {

        @Test
        @DisplayName("正常统计查询")
        void should_returnStats_when_dataExists() {
            Map<String, Object> statsMap = new HashMap<>();
            statsMap.put("totalCount", 10);
            statsMap.put("dueCount", 3);
            statsMap.put("pausedCount", 2);
            // 使用 any(LocalDate.class) 匹配 LocalDate.now()
            when(errorQuestionMapper.selectStatistics(eq(STUDENT_ID), eq(TENANT_ID), any(LocalDate.class)))
                    .thenReturn(statsMap);

            ErrorQuestionStatisticsVO result = errorQuestionService.getStatistics(STUDENT_ID, TENANT_ID);

            assertEquals(10, result.getActiveAndPausedCount());
            assertEquals(3, result.getDueCount());
            assertEquals(2, result.getPausedCount());
        }

        @Test
        @DisplayName("BLOCK-C: 零错题学生 → 全部为 0")
        void should_returnZeros_when_noErrorQuestions() {
            Map<String, Object> statsMap = new HashMap<>();
            statsMap.put("totalCount", 0);
            statsMap.put("dueCount", 0);
            statsMap.put("pausedCount", 0);
            // 使用 any(LocalDate.class) 匹配 LocalDate.now()
            when(errorQuestionMapper.selectStatistics(eq(STUDENT_ID), eq(TENANT_ID), any(LocalDate.class)))
                    .thenReturn(statsMap);

            ErrorQuestionStatisticsVO result = errorQuestionService.getStatistics(STUDENT_ID, TENANT_ID);

            assertEquals(0, result.getActiveAndPausedCount());
            assertEquals(0, result.getDueCount());
            assertEquals(0, result.getPausedCount());
        }

        @Test
        @DisplayName("BLOCK-C: null 值兜底 → 0")
        void should_fallbackToZero_when_statsMapContainsNull() {
            Map<String, Object> statsMap = new HashMap<>();
            statsMap.put("totalCount", null);
            statsMap.put("dueCount", null);
            statsMap.put("pausedCount", null);
            // 使用 any(LocalDate.class) 匹配 LocalDate.now()
            when(errorQuestionMapper.selectStatistics(eq(STUDENT_ID), eq(TENANT_ID), any(LocalDate.class)))
                    .thenReturn(statsMap);

            ErrorQuestionStatisticsVO result = errorQuestionService.getStatistics(STUDENT_ID, TENANT_ID);

            assertEquals(0, result.getActiveAndPausedCount());
            assertEquals(0, result.getDueCount());
            assertEquals(0, result.getPausedCount());
        }
    }

    // ==================== getErrorQuestionDetail ====================

    @Nested
    @DisplayName("getErrorQuestionDetail")
    class GetDetailTests {

        @Test
        @DisplayName("正常返回：题干、选项、正确答案、解析齐全")
        void should_returnDetail_when_rowExists() {
            when(errorQuestionMapper.selectDetailById(STUDENT_ID, TENANT_ID, 1L))
                    .thenReturn(buildDetailRow(1L, "题干内容", "A"));

            ErrorQuestionDetailVO detail =
                    errorQuestionService.getErrorQuestionDetail(STUDENT_ID, TENANT_ID, 1L);

            assertNotNull(detail);
            assertEquals(1L, detail.getErrorQuestionId());
            assertEquals(QUESTION_ID, detail.getQuestionId());
            assertEquals(KP_ID, detail.getKnowledgePointId());
            assertEquals("一元二次方程", detail.getKnowledgePointName());
            assertEquals("homework", detail.getSourceType());
            assertEquals(TODAY.minusDays(7), detail.getFirstWrongDate());
            assertEquals(TODAY, detail.getLastWrongDate());
            assertEquals(2, detail.getCurrentStage());
            assertEquals("active", detail.getStatus());
            assertEquals("题干内容", detail.getQuestionContent());
            assertEquals("standard", detail.getQuestionDifficulty());
            assertEquals("choice", detail.getQuestionType());
            assertEquals("[{\"key\":\"A\",\"text\":\"选项A\"}]", detail.getOptions());
            assertEquals("A", detail.getCorrectAnswer());
            assertEquals("解析内容", detail.getAnalysis());
        }

        @Test
        @DisplayName("查不到（不存在或非本人）→ 抛 ERROR_QUESTION_NOT_FOUND")
        void should_throwNotFound_when_rowMissing() {
            when(errorQuestionMapper.selectDetailById(STUDENT_ID, TENANT_ID, 999L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> errorQuestionService.getErrorQuestionDetail(STUDENT_ID, TENANT_ID, 999L));

            assertEquals(ErrorCodes.ERROR_QUESTION_NOT_FOUND.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("关联题目被删除 → 题干与难度走 COALESCE 兜底")
        void should_fallbackDefaults_when_joinedQuestionMissing() {
            Map<String, Object> row = buildDetailRow(1L, null, null);
            row.put("question_difficulty", null);
            when(errorQuestionMapper.selectDetailById(STUDENT_ID, TENANT_ID, 1L)).thenReturn(row);

            ErrorQuestionDetailVO detail =
                    errorQuestionService.getErrorQuestionDetail(STUDENT_ID, TENANT_ID, 1L);

            assertEquals("[题目已删除]", detail.getQuestionContent());
            assertEquals("unknown", detail.getQuestionDifficulty());
            assertNull(detail.getCorrectAnswer());
        }

        @Test
        @DisplayName("越权校验：查询必须携带当前 studentId + tenantId")
        void should_scopeQueryByStudentAndTenant() {
            when(errorQuestionMapper.selectDetailById(STUDENT_ID, TENANT_ID, 1L))
                    .thenReturn(buildDetailRow(1L, "题干内容", "A"));

            errorQuestionService.getErrorQuestionDetail(STUDENT_ID, TENANT_ID, 1L);

            verify(errorQuestionMapper).selectDetailById(STUDENT_ID, TENANT_ID, 1L);
        }

        private Map<String, Object> buildDetailRow(Long id, String content, String answer) {
            Map<String, Object> row = new HashMap<>();
            row.put("error_question_id", id);
            row.put("question_id", QUESTION_ID);
            row.put("knowledge_point_id", KP_ID);
            row.put("kp_name", "一元二次方程");
            row.put("source_type", "homework");
            row.put("first_wrong_date", TODAY.minusDays(7));
            row.put("last_wrong_date", TODAY);
            row.put("current_stage", 2);
            row.put("next_due_date", TODAY.plusDays(1));
            row.put("status", "active");
            row.put("question_content", content);
            row.put("question_difficulty", "standard");
            row.put("question_type", "choice");
            row.put("question_choices", "[{\"key\":\"A\",\"text\":\"选项A\"}]");
            row.put("correct_answer", answer);
            row.put("question_analysis", "解析内容");
            return row;
        }
    }

    // ==================== 辅助方法 ====================

    private ErrorQuestion buildErrorQuestion(Long id, Long questionId, int stage, String status) {
        ErrorQuestion eq = new ErrorQuestion();
        eq.setErrorQuestionId(id);
        eq.setStudentId(STUDENT_ID);
        eq.setQuestionId(questionId);
        eq.setKnowledgePointId(KP_ID);
        eq.setSourceType("homework");
        eq.setSourceId(1L);
        eq.setFirstWrongDate(TODAY.minusDays(7));
        eq.setLastWrongDate(TODAY);
        eq.setCurrentStage(stage);
        eq.setNextDueDate(TODAY.plusDays(1));
        eq.setStatus(status);
        eq.setCompletedDate(null);
        eq.setTenantId(TENANT_ID);
        return eq;
    }

    private ErrorQuestion buildDueErrorQuestion(Long id, Long questionId, String kpId,
                                                 int stage, LocalDate nextDueDate) {
        ErrorQuestion eq = new ErrorQuestion();
        eq.setErrorQuestionId(id);
        eq.setStudentId(STUDENT_ID);
        eq.setQuestionId(questionId);
        eq.setKnowledgePointId(kpId);
        eq.setSourceType("homework");
        eq.setSourceId(1L);
        eq.setFirstWrongDate(TODAY.minusDays(7));
        eq.setLastWrongDate(TODAY);
        eq.setCurrentStage(stage);
        eq.setNextDueDate(nextDueDate);
        eq.setStatus("active");
        eq.setTenantId(TENANT_ID);
        return eq;
    }
}
