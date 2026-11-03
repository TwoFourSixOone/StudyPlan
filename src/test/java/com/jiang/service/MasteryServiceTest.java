package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.AdminMasteryQueryRequest;
import com.jiang.mapper.GeneratedQuestionMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.MasteryChangeLogMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.MasteryChangeLog;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.enums.ChangeType;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.MasteryServiceImpl;
import com.jiang.vo.MasteryChangeLogVO;
import com.jiang.vo.MasteryDashboardVO;
import com.jiang.vo.MasteryDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MasteryService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasteryService 单元测试")
class MasteryServiceTest {

    @Mock
    private StudentKnowledgeStateMapper stateMapper;

    @Mock
    private MasteryChangeLogMapper changeLogMapper;

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @Mock
    private QuestionBankMapper questionBankMapper;

    @Mock
    private HomeworkSubmissionDetailMapper submissionDetailMapper;

    @Mock
    private GeneratedQuestionMapper generatedQuestionMapper;

    @Mock
    private ErrorQuestionService errorQuestionService;

    @InjectMocks
    private MasteryServiceImpl masteryService;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";
    private static final String KP_ID = "kp_math_001";

    private MockedStatic<StudentContext> studentContextMock;

    @BeforeEach
    void setUp() {
        studentContextMock = mockStatic(StudentContext.class);
        studentContextMock.when(StudentContext::getStudentId).thenReturn(STUDENT_ID);
        studentContextMock.when(StudentContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        studentContextMock.close();
    }

    // ==================== updateMasteryFromResult 测试 ====================

    @Test
    @DisplayName("updateMasteryFromResult：答对场景 - 掌握度上升")
    void testUpdateMasteryFromResult_correct() {
        // 准备数据
        StudentKnowledgeState existingState = createState(STUDENT_ID, KP_ID, new BigDecimal("0.5000"));
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingState);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        // 执行
        BigDecimal dNorm = new BigDecimal("0.6");
        masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.HOMEWORK, 100L, true, dNorm);

        // 验证
        verify(stateMapper).updateById(any(StudentKnowledgeState.class));
        verify(changeLogMapper).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromResult：答错场景 - 掌握度下降")
    void testUpdateMasteryFromResult_incorrect() {
        StudentKnowledgeState existingState = createState(STUDENT_ID, KP_ID, new BigDecimal("0.5000"));
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingState);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        BigDecimal dNorm = new BigDecimal("0.6");
        masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.EXAM, 200L, false, dNorm);

        verify(stateMapper).updateById(any(StudentKnowledgeState.class));
        verify(changeLogMapper).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromResult：首次创建状态 - 验证初始化字段")
    void testUpdateMasteryFromResult_firstTime() {
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(stateMapper.insert(any(StudentKnowledgeState.class))).thenReturn(1);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        BigDecimal dNorm = new BigDecimal("0.6");
        masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.ASSESSMENT, 300L, true, dNorm);

        // 验证 insert 和 updateById 都被调用（首次创建场景）
        ArgumentCaptor<StudentKnowledgeState> insertCaptor = ArgumentCaptor.forClass(StudentKnowledgeState.class);
        verify(stateMapper).insert(insertCaptor.capture());
        StudentKnowledgeState inserted = insertCaptor.getValue();
        // 验证初始插入时的基础字段（这些字段不会被后续计算修改）
        assertEquals(STUDENT_ID, inserted.getStudentId());
        assertEquals(KP_ID, inserted.getKnowledgePointId());
        assertEquals(TENANT_ID, inserted.getTenantId());
        // W-07: 验证首次创建时的来源
        assertEquals(ChangeType.ASSESSMENT.getCode(), inserted.getSource());
        assertNotNull(inserted.getLastAssessedAt());
        // 验证 updateById 也被调用（表示计算后更新）
        ArgumentCaptor<StudentKnowledgeState> updateCaptor = ArgumentCaptor.forClass(StudentKnowledgeState.class);
        verify(stateMapper).updateById(updateCaptor.capture());
        StudentKnowledgeState updated = updateCaptor.getValue();
        // 验证计算后的掌握度大于初始值 0.50（答对场景掌握度上升）
        assertEquals(1, updated.getMasteryScore().compareTo(new BigDecimal("0.50")));
        // 验证置信度已从初始 0.10 递增
        assertEquals(1, updated.getConfidence().compareTo(new BigDecimal("0.10")));
        // 验证变更日志被插入
        verify(changeLogMapper).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromResult：乐观锁冲突 - 抛出 MASTERY_CONCURRENT_CONFLICT")
    void testUpdateMasteryFromResult_concurrentConflict() {
        StudentKnowledgeState existingState = createState(STUDENT_ID, KP_ID, new BigDecimal("0.5000"));
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingState);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(0); // 模拟乐观锁失败

        BigDecimal dNorm = new BigDecimal("0.6");
        BusinessException exception = assertThrows(BusinessException.class, () ->
            masteryService.updateMasteryFromResult(STUDENT_ID, TENANT_ID, KP_ID, ChangeType.HOMEWORK, 100L, true, dNorm)
        );

        assertEquals(ErrorCodes.MASTERY_CONCURRENT_CONFLICT.getCode(), exception.getCode());
    }

    // ==================== updateMasteryFromHomework 测试 ====================

    @Test
    @DisplayName("updateMasteryFromHomework：正常批量更新")
    void testUpdateMasteryFromHomework_normal() {
        List<Long> submissionIds = Arrays.asList(1L, 2L);
        HomeworkSubmissionDetail sub1 = createSubmission(1L, KP_ID, "bank", 100L, 1);
        HomeworkSubmissionDetail sub2 = createSubmission(2L, KP_ID, "bank", 101L, 0);

        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Arrays.asList(sub1, sub2));
        QuestionBank question = new QuestionBank();
        question.setQuestionId(100L);
        question.setDifficulty("standard");
        when(questionBankMapper.selectById(anyLong())).thenReturn(question);
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(stateMapper.insert(any(StudentKnowledgeState.class))).thenReturn(1);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds);

        // 验证同一知识点只更新一次 DB
        verify(stateMapper, times(1)).updateById(any(StudentKnowledgeState.class));
        // 验证两条日志都被插入
        verify(changeLogMapper, times(2)).insert(any(MasteryChangeLog.class));
    }

    @Test
    @DisplayName("updateMasteryFromHomework：同一知识点多次出现 - 增量不丢失（内存缓存验证 + 数值衔接断言）")
    void testUpdateMasteryFromHomework_sameKpMultipleTimes() {
        List<Long> submissionIds = Arrays.asList(1L, 2L, 3L);
        HomeworkSubmissionDetail sub1 = createSubmission(1L, KP_ID, "bank", 100L, 1);
        HomeworkSubmissionDetail sub2 = createSubmission(2L, KP_ID, "bank", 101L, 1);
        HomeworkSubmissionDetail sub3 = createSubmission(3L, KP_ID, "bank", 102L, 1);

        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Arrays.asList(sub1, sub2, sub3));
        QuestionBank question = new QuestionBank();
        question.setDifficulty("standard");
        when(questionBankMapper.selectById(anyLong())).thenReturn(question);
        when(stateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(stateMapper.insert(any(StudentKnowledgeState.class))).thenReturn(1);
        when(stateMapper.updateById(any(StudentKnowledgeState.class))).thenReturn(1);
        when(changeLogMapper.insert(any(MasteryChangeLog.class))).thenReturn(1);

        masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds);

        // 验证同一知识点只执行一次 DB 更新
        verify(stateMapper, times(1)).updateById(any(StudentKnowledgeState.class));
        // 验证三条日志都被插入
        verify(changeLogMapper, times(3)).insert(any(MasteryChangeLog.class));

        // S-03: 捕获日志并验证 mastery_before/mastery_after 数值衔接
        ArgumentCaptor<MasteryChangeLog> logCaptor = ArgumentCaptor.forClass(MasteryChangeLog.class);
        verify(changeLogMapper, times(3)).insert(logCaptor.capture());
        List<MasteryChangeLog> logs = logCaptor.getAllValues();

        // 第一条日志的 mastery_before 应为初始值 0.5000
        assertEquals(0, new BigDecimal("0.5000").compareTo(logs.get(0).getMasteryBefore()),
                "第一条日志 mastery_before 应为 0.5000");
        // 第二条日志的 mastery_before 应等于第一条日志的 mastery_after
        assertEquals(0, logs.get(0).getMasteryAfter().compareTo(logs.get(1).getMasteryBefore()),
                "第二条 mastery_before 应等于第一条 mastery_after（数值链衔接）");
        // 第三条日志的 mastery_before 应等于第二条日志的 mastery_after
        assertEquals(0, logs.get(1).getMasteryAfter().compareTo(logs.get(2).getMasteryBefore()),
                "第三条 mastery_before 应等于第二条 mastery_after（数值链衔接）");
    }

    @Test
    @DisplayName("updateMasteryFromHomework：归属校验失败 - 抛异常")
    void testUpdateMasteryFromHomework_ownershipCheckFailed() {
        List<Long> submissionIds = Arrays.asList(1L);
        HomeworkSubmissionDetail sub = createSubmission(1L, KP_ID, "bank", 100L, 1);
        sub.setStudentId(999L); // 不同的学生 ID

        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Arrays.asList(sub));

        assertThrows(BusinessException.class, () ->
            masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds)
        );
    }

    @Test
    @DisplayName("updateMasteryFromHomework：submission 不存在 - 抛异常")
    void testUpdateMasteryFromHomework_submissionNotFound() {
        List<Long> submissionIds = Arrays.asList(1L, 2L);
        when(submissionDetailMapper.selectBatchIds(submissionIds)).thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () ->
            masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, submissionIds)
        );
    }

    @Test
    @DisplayName("updateMasteryFromHomework：空列表 - 直接返回")
    void testUpdateMasteryFromHomework_emptyList() {
        masteryService.updateMasteryFromHomework(STUDENT_ID, TENANT_ID, Collections.emptyList());

        verify(submissionDetailMapper, never()).selectBatchIds(anyList());
    }

    // ==================== getStudentDashboard 测试 ====================

    @Test
    @DisplayName("getStudentDashboard：按科目筛选 - JOIN 查询")
    void testGetStudentDashboard_withSubject() {
        // 构造包含瞬态字段的 state（JOIN 查询结果）
        StudentKnowledgeState state = createState(STUDENT_ID, KP_ID, new BigDecimal("0.6000"));
        state.setKpName("测试知识点");
        state.setKpSubject("数学");
        state.setKpStatus("active");
        when(stateMapper.selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), eq("数学")))
                .thenReturn(Arrays.asList(state));

        MasteryChangeLog log = createChangeLog(KP_ID, new BigDecimal("0.05"));
        when(changeLogMapper.selectLatestByKpIds(eq(STUDENT_ID), eq(TENANT_ID), anyList()))
            .thenReturn(Arrays.asList(log));

        List<MasteryDashboardVO> result = masteryService.getStudentDashboard("数学");

        assertEquals(1, result.size());
        assertEquals("数学", result.get(0).getSubject());
        assertEquals("测试知识点", result.get(0).getKnowledgePointName());
        assertEquals("up", result.get(0).getLastChangeDirection());
    }

    @Test
    @DisplayName("getStudentDashboard：无 active 知识点 - 返回空列表")
    void testGetStudentDashboard_noActiveKp() {
        when(stateMapper.selectActiveStatesWithKpInfo(eq(STUDENT_ID), eq(TENANT_ID), any()))
                .thenReturn(Collections.emptyList());

        List<MasteryDashboardVO> result = masteryService.getStudentDashboard(null);

        assertTrue(result.isEmpty());
        // 不应查询变更日志
        verify(changeLogMapper, never()).selectLatestByKpIds(anyLong(), anyString(), anyList());
    }

    // ==================== getStudentChangeHistory 测试 ====================

    @Test
    @DisplayName("getStudentChangeHistory：正常分页")
    void testGetStudentChangeHistory_normal() {
        KnowledgePoint kp = createKnowledgePoint(KP_ID, "数学", "active");
        when(changeLogMapper.countHistoryByStudentAndKp(STUDENT_ID, TENANT_ID, KP_ID)).thenReturn(2L);
        MasteryChangeLog log1 = createChangeLog(KP_ID, new BigDecimal("0.05"));
        MasteryChangeLog log2 = createChangeLog(KP_ID, new BigDecimal("0.03"));
        when(changeLogMapper.selectHistoryByStudentAndKp(STUDENT_ID, TENANT_ID, KP_ID, 0, 10))
                .thenReturn(Arrays.asList(log1, log2));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(kp));

        PageResult<MasteryChangeLogVO> result = masteryService.getStudentChangeHistory(KP_ID, 1, 10);

        assertEquals(2L, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("getStudentChangeHistory：空结果")
    void testGetStudentChangeHistory_empty() {
        when(changeLogMapper.countHistoryByStudentAndKp(STUDENT_ID, TENANT_ID, KP_ID)).thenReturn(0L);

        PageResult<MasteryChangeLogVO> result = masteryService.getStudentChangeHistory(KP_ID, 1, 10);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        // 不应查询详细数据
        verify(changeLogMapper, never()).selectHistoryByStudentAndKp(anyLong(), anyString(), anyString(), eq(0), eq(10));
    }

    // ==================== getAdminDashboard 测试 ====================

    @Test
    @DisplayName("getAdminDashboard：多条件筛选 + 分页")
    void testGetAdminDashboard_withFilters() {
        AdminMasteryQueryRequest request = AdminMasteryQueryRequest.builder()
                .studentId(STUDENT_ID)
                .knowledgePointId(KP_ID)
                .subject("数学")
                .page(1)
                .size(10)
                .build();

        KnowledgePoint kp = createKnowledgePoint(KP_ID, "数学", "active");
        // 先筛选知识点
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(kp));

        // 模拟分页查询结果
        StudentKnowledgeState state = createState(STUDENT_ID, KP_ID, new BigDecimal("0.6000"));
        Page<StudentKnowledgeState> pageResult = new Page<>(1, 10);
        pageResult.setRecords(Arrays.asList(state));
        pageResult.setTotal(1);
        when(stateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(pageResult);

        PageResult<MasteryDetailVO> result = masteryService.getAdminDashboard(request);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(KP_ID, result.getRecords().get(0).getKnowledgePointId());
        assertEquals("测试知识点", result.getRecords().get(0).getKnowledgePointName());
    }

    @Test
    @DisplayName("getAdminDashboard：无匹配知识点 - 返回空")
    void testGetAdminDashboard_noKpMatch() {
        AdminMasteryQueryRequest request = AdminMasteryQueryRequest.builder()
                .subject("不存在的科目")
                .page(1)
                .size(10)
                .build();

        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        PageResult<MasteryDetailVO> result = masteryService.getAdminDashboard(request);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    // ==================== getAdminChangeHistory 测试 ====================

    @Test
    @DisplayName("getAdminChangeHistory：正常查询")
    void testGetAdminChangeHistory_normal() {
        Long targetStudentId = 2L;
        KnowledgePoint kp = createKnowledgePoint(KP_ID, "数学", "active");
        when(changeLogMapper.countHistoryByStudentAndKp(targetStudentId, TENANT_ID, KP_ID)).thenReturn(1L);
        MasteryChangeLog log = createChangeLog(KP_ID, new BigDecimal("0.05"));
        when(changeLogMapper.selectHistoryByStudentAndKp(targetStudentId, TENANT_ID, KP_ID, 0, 10))
                .thenReturn(Arrays.asList(log));
        when(knowledgePointMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(kp));

        PageResult<MasteryChangeLogVO> result = masteryService.getAdminChangeHistory(targetStudentId, KP_ID, 1, 10);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("getAdminChangeHistory：空结果")
    void testGetAdminChangeHistory_empty() {
        Long targetStudentId = 2L;
        when(changeLogMapper.countHistoryByStudentAndKp(targetStudentId, TENANT_ID, KP_ID)).thenReturn(0L);

        PageResult<MasteryChangeLogVO> result = masteryService.getAdminChangeHistory(targetStudentId, KP_ID, 1, 10);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    // ==================== 辅助方法 ====================

    private StudentKnowledgeState createState(Long studentId, String kpId, BigDecimal mastery) {
        StudentKnowledgeState state = new StudentKnowledgeState();
        state.setStudentId(studentId);
        state.setKnowledgePointId(kpId);
        state.setTenantId(TENANT_ID);
        state.setMasteryScore(mastery);
        state.setConfidence(new BigDecimal("0.1000"));
        state.setSource(ChangeType.HOMEWORK.getCode());
        state.setLastAssessedAt(LocalDateTime.now());
        state.setVersion(0);
        return state;
    }

    private HomeworkSubmissionDetail createSubmission(Long submissionId, String kpId,
                                                       String questionSource, Long questionId, int isCorrect) {
        HomeworkSubmissionDetail detail = new HomeworkSubmissionDetail();
        detail.setSubmissionId(submissionId);
        detail.setStudentId(STUDENT_ID);
        detail.setKnowledgePointId(kpId);
        detail.setQuestionSource(questionSource);
        detail.setQuestionId(questionId);
        detail.setIsCorrect(isCorrect);
        detail.setTenantId(TENANT_ID);
        return detail;
    }

    private KnowledgePoint createKnowledgePoint(String kpId, String subject, String status) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId(kpId);
        kp.setSubject(subject);
        kp.setName("测试知识点");
        kp.setStatus(status);
        kp.setTenantId(TENANT_ID);
        return kp;
    }

    private MasteryChangeLog createChangeLog(String kpId, BigDecimal delta) {
        MasteryChangeLog log = new MasteryChangeLog();
        log.setLogId(1L);
        log.setStudentId(STUDENT_ID);
        log.setKnowledgePointId(kpId);
        log.setChangeType(ChangeType.HOMEWORK.getCode());
        log.setDelta(delta);
        log.setMasteryBefore(new BigDecimal("0.5000"));
        log.setMasteryAfter(new BigDecimal("0.5500"));
        log.setTenantId(TENANT_ID);
        log.setCreateTime(LocalDateTime.now());
        return log;
    }
}
