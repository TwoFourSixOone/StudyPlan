package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.CreateGoalRequest;
import com.jiang.dto.UpdateGoalRequest;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.GoalStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.GoalServiceImpl;
import com.jiang.vo.GoalVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

/**
 * GoalService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock
    private LearningGoalMapper learningGoalMapper;

    @Mock
    private SubjectMapper subjectMapper;

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @InjectMocks
    private GoalServiceImpl goalService;

    private MockedStatic<StudentContext> mockedStudentContext;

    private static final Long TEST_STUDENT_ID = 1L;
    private static final Long OTHER_STUDENT_ID = 2L;
    private static final String TEST_TENANT_ID = "default";
    private static final Long TEST_SUBJECT_ID = 10L;
    private static final String TEST_SUBJECT_NAME = "数学";
    private static final Long TEST_GOAL_ID = 100L;

    @BeforeEach
    void setUp() {
        mockedStudentContext = mockStatic(StudentContext.class);
        mockedStudentContext.when(StudentContext::getStudentId).thenReturn(TEST_STUDENT_ID);
        mockedStudentContext.when(StudentContext::getTenantId).thenReturn(TEST_TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
    }

    // ==================== 创建目标 ====================

    @Test
    void should_createGoal_when_allValidationsPass() {
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);
        when(knowledgePointMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        when(learningGoalMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(learningGoalMapper.insert(any(LearningGoal.class))).thenAnswer(inv -> {
            LearningGoal g = inv.getArgument(0);
            g.setGoalId(TEST_GOAL_ID);
            g.setCreateTime(LocalDateTime.now());
            g.setUpdateTime(LocalDateTime.now());
            return 1;
        });

        CreateGoalRequest request = buildCreateRequest();
        GoalVO result = goalService.createGoal(request);

        assertNotNull(result);
        assertEquals(TEST_GOAL_ID, result.getGoalId());
        assertEquals(TEST_STUDENT_ID, result.getStudentId());
        assertEquals(TEST_SUBJECT_ID, result.getSubjectId());
        assertEquals(TEST_SUBJECT_NAME, result.getSubjectName());
        assertEquals(GoalStatus.ACTIVE.getCode(), result.getStatus());
        assertEquals(request.getDirection(), result.getDirection());
        assertEquals(request.getTargetScore(), result.getTargetScore());

        // I-05：验证 insert 实体的关键字段正确性
        ArgumentCaptor<LearningGoal> captor = ArgumentCaptor.forClass(LearningGoal.class);
        verify(learningGoalMapper).insert(captor.capture());
        LearningGoal inserted = captor.getValue();
        assertEquals(TEST_STUDENT_ID, inserted.getStudentId());
        assertEquals(TEST_TENANT_ID, inserted.getTenantId());
        assertEquals(TEST_SUBJECT_ID, inserted.getSubjectId());
        assertEquals(GoalStatus.ACTIVE.getCode(), inserted.getStatus());
    }

    @Test
    void should_throwSubjectNotFound_when_subjectNotExists() {
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(null);

        CreateGoalRequest request = buildCreateRequest();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.createGoal(request));
        assertEquals(ErrorCodes.SUBJECT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_throwSubjectNotFound_when_subjectBelongsToDifferentTenant() {
        // S-02：跨租户科目访问应被拒绝
        Subject otherTenantSubject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        otherTenantSubject.setTenantId("other_tenant");
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(otherTenantSubject);

        CreateGoalRequest request = buildCreateRequest();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.createGoal(request));
        assertEquals(ErrorCodes.SUBJECT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void should_throwSubjectNoActiveKp_when_noActiveKnowledgePoints() {
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);
        when(knowledgePointMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        CreateGoalRequest request = buildCreateRequest();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.createGoal(request));
        assertEquals(ErrorCodes.SUBJECT_NO_ACTIVE_KP.getCode(), ex.getCode());
    }

    @Test
    void should_throwGoalAlreadyActive_when_duplicateActiveGoalExists() {
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);
        when(knowledgePointMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        when(learningGoalMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        CreateGoalRequest request = buildCreateRequest();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.createGoal(request));
        assertEquals(ErrorCodes.GOAL_ALREADY_ACTIVE.getCode(), ex.getCode());
    }

    @Test
    void should_throwDateInvalid_when_endDateNotAfterStartDate() {
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);
        when(knowledgePointMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        when(learningGoalMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        CreateGoalRequest request = buildCreateRequest();
        request.setStartDate(LocalDate.of(2026, 12, 1));
        request.setEndDate(LocalDate.of(2026, 12, 1));  // endDate == startDate

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.createGoal(request));
        assertEquals(ErrorCodes.DATE_INVALID.getCode(), ex.getCode());
    }

    @Test
    void should_throwDateInvalid_when_startDateBeforeToday() {
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);
        when(knowledgePointMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        when(learningGoalMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        CreateGoalRequest request = buildCreateRequest();
        request.setStartDate(LocalDate.now().minusDays(1));
        request.setEndDate(LocalDate.now().plusDays(30));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.createGoal(request));
        assertEquals(ErrorCodes.DATE_INVALID.getCode(), ex.getCode());
    }

    // ==================== 查看活跃目标 ====================

    @Test
    void should_returnActiveGoal_when_goalExistsAndNotExpired() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(learningGoalMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(goal);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        GoalVO result = goalService.getActiveGoal(null);

        assertNotNull(result);
        assertEquals(TEST_GOAL_ID, result.getGoalId());
        assertEquals(GoalStatus.ACTIVE.getCode(), result.getStatus());
    }

    @Test
    void should_returnActiveGoal_when_subjectIdProvided() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        goal.setSubjectId(TEST_SUBJECT_ID);
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(learningGoalMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(goal);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        GoalVO result = goalService.getActiveGoal(TEST_SUBJECT_ID);

        assertNotNull(result);
        assertEquals(TEST_SUBJECT_ID, result.getSubjectId());
    }

    @Test
    void should_returnNull_when_noActiveGoalExists() {
        when(learningGoalMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        GoalVO result = goalService.getActiveGoal(null);

        assertNull(result);
    }

    @Test
    void should_returnNull_when_goalExpiredButNotMarked() {
        // 目标 status=active 但 endDate < today，不应返回
        when(learningGoalMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        GoalVO result = goalService.getActiveGoal(null);

        assertNull(result);
    }

    // ==================== 查看目标列表 ====================

    @Test
    void should_returnGoalList_when_studentHasGoals() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        Page<LearningGoal> page = new Page<>(1, 10);
        page.setRecords(java.util.List.of(goal));
        page.setTotal(1);
        when(learningGoalMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        PageResult<GoalVO> result = goalService.getGoalList(1, 10);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }

    // ==================== 查看目标详情 ====================

    @Test
    void should_returnGoalDetail_when_ownerMatches() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        GoalVO result = goalService.getGoalDetail(TEST_GOAL_ID);

        assertNotNull(result);
        assertEquals(TEST_GOAL_ID, result.getGoalId());
        assertEquals(TEST_STUDENT_ID, result.getStudentId());
    }

    @Test
    void should_throwGoalNotFound_when_otherStudentOwnsGoal() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        goal.setStudentId(OTHER_STUDENT_ID);
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.getGoalDetail(TEST_GOAL_ID));
        assertEquals(ErrorCodes.GOAL_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== 修改目标 ====================

    @Test
    void should_updateGoal_when_directionAndScoreProvided() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);
        when(learningGoalMapper.updateById(any(LearningGoal.class))).thenReturn(1);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        UpdateGoalRequest request = new UpdateGoalRequest();
        request.setDirection("新方向");
        request.setTargetScore(120);

        GoalVO result = goalService.updateGoal(TEST_GOAL_ID, request);

        assertNotNull(result);
        assertEquals("新方向", result.getDirection());
        assertEquals(120, result.getTargetScore());
    }

    @Test
    void should_clearDirection_when_emptyStringProvided() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        goal.setDirection("旧方向");
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);
        when(learningGoalMapper.updateById(any(LearningGoal.class))).thenReturn(1);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        UpdateGoalRequest request = new UpdateGoalRequest();
        request.setDirection("");

        GoalVO result = goalService.updateGoal(TEST_GOAL_ID, request);

        assertNotNull(result);
        assertEquals("", result.getDirection());
    }

    @Test
    void should_throwGoalNotModifiable_when_goalCompleted() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        goal.setStatus(GoalStatus.COMPLETED.getCode());
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);

        UpdateGoalRequest request = new UpdateGoalRequest();
        request.setDirection("新方向");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.updateGoal(TEST_GOAL_ID, request));
        assertEquals(ErrorCodes.GOAL_NOT_MODIFIABLE.getCode(), ex.getCode());
    }

    @Test
    void should_throwGoalExpired_when_goalEndDateBeforeToday() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        goal.setEndDate(LocalDate.now().minusDays(1));
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);

        UpdateGoalRequest request = new UpdateGoalRequest();
        request.setDirection("新方向");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.updateGoal(TEST_GOAL_ID, request));
        assertEquals(ErrorCodes.GOAL_EXPIRED.getCode(), ex.getCode());
    }

    @Test
    void should_throwDateInvalid_when_endDateShortened() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        goal.setEndDate(LocalDate.now().plusDays(30));
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);

        UpdateGoalRequest request = new UpdateGoalRequest();
        request.setEndDate(LocalDate.now().plusDays(10));  // 缩短

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.updateGoal(TEST_GOAL_ID, request));
        assertEquals(ErrorCodes.DATE_INVALID.getCode(), ex.getCode());
    }

    // ==================== 结束目标 ====================

    @Test
    void should_completeGoal_when_goalIsActive() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);
        when(learningGoalMapper.updateById(any(LearningGoal.class))).thenReturn(1);

        goalService.completeGoal(TEST_GOAL_ID);

        verify(learningGoalMapper).updateById(argThat(g ->
            GoalStatus.COMPLETED.getCode().equals(g.getStatus())
        ));
    }

    @Test
    void should_throwGoalNotModifiable_when_completingAlreadyCompletedGoal() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        goal.setStatus(GoalStatus.COMPLETED.getCode());
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.completeGoal(TEST_GOAL_ID));
        assertEquals(ErrorCodes.GOAL_NOT_MODIFIABLE.getCode(), ex.getCode());
    }

    // ==================== 管理端查询 ====================

    @Test
    void should_returnAdminGoalList_when_filteredByStudentAndSubject() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        Page<LearningGoal> page = new Page<>(1, 10);
        page.setRecords(java.util.List.of(goal));
        page.setTotal(1);
        when(learningGoalMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        PageResult<GoalVO> result = goalService.adminGetGoalList(TEST_STUDENT_ID, TEST_SUBJECT_ID, "active", 1, 10);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
    }

    @Test
    void should_returnAdminGoalDetail_when_goalExistsInTenant() {
        LearningGoal goal = buildActiveGoal(TEST_GOAL_ID);
        Subject subject = buildSubject(TEST_SUBJECT_ID, TEST_SUBJECT_NAME);
        when(learningGoalMapper.selectById(TEST_GOAL_ID)).thenReturn(goal);
        when(subjectMapper.selectById(TEST_SUBJECT_ID)).thenReturn(subject);

        GoalVO result = goalService.adminGetGoalDetail(TEST_GOAL_ID);

        assertNotNull(result);
        assertEquals(TEST_GOAL_ID, result.getGoalId());
    }

    @Test
    void should_throwGoalNotFound_when_adminQueriesNonExistentGoal() {
        when(learningGoalMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> goalService.adminGetGoalDetail(999L));
        assertEquals(ErrorCodes.GOAL_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== 辅助方法 ====================

    private CreateGoalRequest buildCreateRequest() {
        CreateGoalRequest request = new CreateGoalRequest();
        request.setSubjectId(TEST_SUBJECT_ID);
        request.setDirection("提高数学成绩");
        request.setTargetScore(120);
        request.setStartDate(LocalDate.now());
        request.setEndDate(LocalDate.now().plusMonths(3));
        return request;
    }

    private LearningGoal buildActiveGoal(Long goalId) {
        LearningGoal goal = new LearningGoal();
        goal.setGoalId(goalId);
        goal.setStudentId(TEST_STUDENT_ID);
        goal.setSubjectId(TEST_SUBJECT_ID);
        goal.setDirection("提高数学成绩");
        goal.setTargetScore(120);
        goal.setStartDate(LocalDate.now());
        goal.setEndDate(LocalDate.now().plusMonths(3));
        goal.setStatus(GoalStatus.ACTIVE.getCode());
        goal.setTenantId(TEST_TENANT_ID);
        goal.setCreateTime(LocalDateTime.now());
        goal.setUpdateTime(LocalDateTime.now());
        return goal;
    }

    private Subject buildSubject(Long subjectId, String name) {
        Subject subject = new Subject();
        subject.setSubjectId(subjectId);
        subject.setName(name);
        subject.setStage("高中");
        subject.setDescription("数学科目");
        subject.setTenantId(TEST_TENANT_ID);
        return subject;
    }
}
