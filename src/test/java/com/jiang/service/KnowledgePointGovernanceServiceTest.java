package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.mapper.KnowledgePointDependencyMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.KnowledgePointDependency;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.KnowledgePointGovernanceServiceImpl;
import com.jiang.vo.KnowledgePointDetailVO;
import com.jiang.vo.KnowledgePointListVO;
import com.jiang.vo.KnowledgePointOverdueVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 知识点治理服务单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@ExtendWith(MockitoExtension.class)
class KnowledgePointGovernanceServiceTest {

    private static final String TENANT_ID = "test-tenant-001";
    private static final String KP_ID = "kp_001";
    private static final String KP_NAME = "测试知识点";
    private static final String SUBJECT = "数学";

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @Mock
    private QuestionBankMapper questionBankMapper;

    @Mock
    private LearningGoalMapper learningGoalMapper;

    @Mock
    private KnowledgePointDependencyMapper dependencyMapper;

    @InjectMocks
    private KnowledgePointGovernanceServiceImpl governanceService;

    private MockedStatic<StudentContext> studentContextMock;

    @BeforeEach
    void setUp() {
        studentContextMock = mockStatic(StudentContext.class);
        studentContextMock.when(StudentContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        studentContextMock.close();
    }

    @Test
    @DisplayName("知识点列表：有效筛选条件 → 返回列表")
    void should_returnList_when_validFilters() {
        // 准备数据
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "active");
        Page<KnowledgePoint> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(kp));
        page.setTotal(1);

        when(knowledgePointMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);
        when(questionBankMapper.countByKnowledgePointIds(anyList(), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        // 执行
        PageResult<KnowledgePointListVO> result = governanceService.getKnowledgePointList(
                SUBJECT, "active", null, 1, 10);

        // 验证
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals(KP_ID, result.getRecords().get(0).getId());
        assertEquals(KP_NAME, result.getRecords().get(0).getName());
        assertEquals(SUBJECT, result.getRecords().get(0).getSubjectName());
        assertEquals("active", result.getRecords().get(0).getStatus());
    }

    @Test
    @DisplayName("知识点详情：知识点存在 → 返回详情")
    void should_returnDetail_when_kpExists() {
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "active");
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(kp);
        when(dependencyMapper.selectByKnowledgePointId(KP_ID, TENANT_ID))
                .thenReturn(Collections.emptyList());
        when(questionBankMapper.countByKnowledgePointIds(eq(List.of(KP_ID)), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        KnowledgePointDetailVO result = governanceService.getKnowledgePointDetail(KP_ID);

        assertNotNull(result);
        assertEquals(KP_ID, result.getId());
        assertEquals(KP_NAME, result.getName());
        assertEquals(SUBJECT, result.getSubjectName());
        assertEquals("active", result.getStatus());
    }

    @Test
    @DisplayName("知识点详情：知识点不存在 → 抛异常")
    void should_throwException_when_kpNotExists() {
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.getKnowledgePointDetail(KP_ID));
        assertEquals(ErrorCodes.KNOWLEDGE_POINT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("启停用：无活跃目标引用 → 成功停用")
    void should_updateStatusToDisabled_when_noActiveGoals() {
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "active");
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(kp);
        when(learningGoalMapper.countByKnowledgePointId(KP_ID, TENANT_ID)).thenReturn(0);
        when(knowledgePointMapper.updateById(any(KnowledgePoint.class))).thenReturn(1);

        assertDoesNotThrow(() -> governanceService.updateStatus(KP_ID, "disabled"));

        verify(knowledgePointMapper).updateById(argThat(updatedKp ->
                "disabled".equals(updatedKp.getStatus())));
    }

    @Test
    @DisplayName("启停用：有活跃目标引用 → 抛异常")
    void should_throwException_when_kpInUse() {
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "active");
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(kp);
        when(learningGoalMapper.countByKnowledgePointId(KP_ID, TENANT_ID)).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.updateStatus(KP_ID, "disabled"));
        assertEquals(ErrorCodes.KP_IN_USE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("启停用：无效状态值 → 抛异常")
    void should_throwException_when_invalidStatus() {
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "active");
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(kp);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.updateStatus(KP_ID, "invalid_status"));
        assertEquals(ErrorCodes.KP_INVALID_STATUS.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("校对标记：pending_review 状态 → 标记为 active")
    void should_markAsReviewed_when_statusPending() {
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "pending_review");
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(kp);
        when(knowledgePointMapper.updateById(any(KnowledgePoint.class))).thenReturn(1);

        assertDoesNotThrow(() -> governanceService.markAsReviewed(KP_ID));

        verify(knowledgePointMapper).updateById(argThat(updatedKp ->
                "active".equals(updatedKp.getStatus())));
    }

    @Test
    @DisplayName("校对标记：已是 active 状态 → 幂等返回成功")
    void should_returnSuccess_when_markAsReviewedAndAlreadyActive() {
        // 修复 BLOCK-9：幂等测试
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "active");
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(kp);

        // 不应抛异常
        assertDoesNotThrow(() -> governanceService.markAsReviewed(KP_ID));

        // 不应执行更新
        verify(knowledgePointMapper, never()).updateById(any(KnowledgePoint.class));
    }

    @Test
    @DisplayName("校对标记：disabled 状态 → 抛异常")
    void should_throwException_when_markAsReviewedAndDisabled() {
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "disabled");
        when(knowledgePointMapper.selectByIdWithTenant(KP_ID, TENANT_ID)).thenReturn(kp);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> governanceService.markAsReviewed(KP_ID));
        assertEquals(ErrorCodes.KP_NOT_PENDING_REVIEW.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("超期列表：有超期知识点 → 返回列表")
    void should_returnOverdueList_when_hasOverdueKp() {
        // 创建 10 天前创建的知识点（超过 7 天）
        KnowledgePoint kp = buildKnowledgePoint(KP_ID, KP_NAME, SUBJECT, "pending_review");
        kp.setCreateTime(LocalDateTime.now().minusDays(10));

        when(knowledgePointMapper.selectOverdue(any(LocalDate.class), eq(TENANT_ID)))
                .thenReturn(Arrays.asList(kp));

        List<KnowledgePointOverdueVO> result = governanceService.getOverdueKnowledgePoints();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(KP_ID, result.get(0).getId());
        assertEquals(KP_NAME, result.get(0).getName());
        assertTrue(result.get(0).getOverdueDays() >= 7);
    }

    @Test
    @DisplayName("超期列表：无超期知识点 → 返回空列表")
    void should_returnEmptyList_when_noOverdueKp() {
        when(knowledgePointMapper.selectOverdue(any(LocalDate.class), eq(TENANT_ID)))
                .thenReturn(Collections.emptyList());

        List<KnowledgePointOverdueVO> result = governanceService.getOverdueKnowledgePoints();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== 辅助方法 ====================

    private KnowledgePoint buildKnowledgePoint(String id, String name, String subject, String status) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId(id);
        kp.setName(name);
        kp.setSubject(subject);
        kp.setStatus(status);
        kp.setTenantId(TENANT_ID);
        kp.setCreateTime(LocalDateTime.now().minusDays(3));
        kp.setUpdateTime(LocalDateTime.now());
        return kp;
    }
}
