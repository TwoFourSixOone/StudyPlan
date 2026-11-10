package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.result.PageResult;
import com.jiang.mapper.*;
import com.jiang.model.entity.*;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.AdminListQueryServiceImpl;
import com.jiang.vo.*;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 管理后端列表查询服务单元测试
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@ExtendWith(MockitoExtension.class)
class AdminListQueryServiceTest {

    private static final String TENANT_ID = "test-tenant-001";

    @Mock
    private QuestionBankMapper questionBankMapper;
    @Mock
    private KnowledgePointDependencyMapper dependencyMapper;
    @Mock
    private CurriculumStandardMapper standardMapper;
    @Mock
    private DataImportLogMapper importLogMapper;
    @Mock
    private DataImportErrorLogMapper importErrorMapper;
    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @InjectMocks
    private AdminListQueryServiceImpl adminListQueryService;

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
    @DisplayName("题目列表：有效筛选条件 → 返回列表")
    void should_returnQuestionList_when_validFilters() {
        QuestionBank q = buildQuestion(1L, "题干内容", "kp_001", "basic", "choice");
        Page<QuestionBank> page = new Page<>(1, 20);
        page.setRecords(Arrays.asList(q));
        page.setTotal(1);

        when(questionBankMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of("knowledge_point_id", "kp_001", "name", "二次函数")));

        PageResult<QuestionListVO> result = adminListQueryService.getQuestionList(
                null, null, null, null, null, null, null, 1, 20);

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        // 修复 IMP-1：验证 questionType 来自 type 字段
        assertEquals("choice", result.getRecords().get(0).getQuestionType());
        assertEquals("二次函数", result.getRecords().get(0).getKnowledgePointName());
    }

    @Test
    @DisplayName("题目列表：按年级筛选 → SQL 条件带 grade，VO 回填年级")
    void should_applyGradeFilter_when_gradeProvided() {
        QuestionBank q = buildQuestion(1L, "题干内容", "kp_001", "basic", "choice");
        q.setGrade(3);
        Page<QuestionBank> page = new Page<>(1, 20);
        page.setRecords(List.of(q));
        page.setTotal(1);

        when(questionBankMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT_ID))).thenReturn(List.of());

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        PageResult<QuestionListVO> result = adminListQueryService.getQuestionList(
                null, null, null, null, null, null, 3, 1, 20);

        verify(questionBankMapper).selectPage(any(Page.class), captor.capture());
        QueryWrapper<?> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("grade"), "wrapper 应带 grade 条件");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(3), "grade 参数应为 3");
        // 产品规则 #11：VO 回填年级
        assertEquals(3, result.getRecords().get(0).getGrade());
    }

    @Test
    @DisplayName("题目列表：年级筛选命中「题目未标注但知识点年级恰好是该年级」的题目")
    void should_matchInheritedGrade_when_questionGradeIsNull() {
        QuestionBank q = buildQuestion(1L, "题干内容", "kp_001", "basic", "choice"); // grade 为空
        Page<QuestionBank> page = new Page<>(1, 20);
        page.setRecords(List.of(q));
        page.setTotal(1);

        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId("kp_001");
        kp.setGradeRange("12");
        when(knowledgePointMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(kp));
        when(questionBankMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT_ID))).thenReturn(List.of());

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        adminListQueryService.getQuestionList(null, null, null, null, null, null, 12, 1, 20);

        verify(questionBankMapper).selectPage(any(Page.class), captor.capture());
        QueryWrapper<?> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("grade"), "应包含题目自身 grade 条件");
        assertTrue(sql.contains("knowledge_point_id"), "应包含继承知识点的 IN 条件");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("kp_001"));
    }

    @Test
    @DisplayName("题目列表：学段优先由知识点年级派生，不依赖课标表")
    void should_deriveStageFromGradeRange_when_gradePresent() {
        QuestionBank q = buildQuestion(1L, "题干内容", "kp_001", "basic", "choice");
        Page<QuestionBank> page = new Page<>(1, 20);
        page.setRecords(List.of(q));
        page.setTotal(1);

        when(questionBankMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT_ID))).thenReturn(List.of());
        Map<String, Object> kpRow = new HashMap<>();
        kpRow.put("knowledge_point_id", "kp_001");
        kpRow.put("subject", "数学");
        kpRow.put("standards", null);
        kpRow.put("grade_range", "7-9");
        when(knowledgePointMapper.selectSubjectAndStandardsByIds(anyList(), eq(TENANT_ID)))
                .thenReturn(List.of(kpRow));

        PageResult<QuestionListVO> result = adminListQueryService.getQuestionList(
                null, null, null, null, null, null, null, 1, 20);

        assertEquals("初中", result.getRecords().get(0).getStage());
        // 题目未标注年级时，VO 带回知识点年级用于前端展示"继承"
        assertEquals("7-9", result.getRecords().get(0).getGradeRange());
        // 年级可派生学段时不应再查课标表
        verify(standardMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("题目列表：keyword 含 % 和 _ → 转义后查询")
    void should_escapeKeyword_when_keywordContainsPercent() {
        // 修复 IMP-3：转义测试
        Page<QuestionBank> page = new Page<>(1, 20);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);

        when(questionBankMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);

        // 不应抛异常
        assertDoesNotThrow(() -> adminListQueryService.getQuestionList(
                null, null, null, "100%_难", null, null, null, 1, 20));
    }

    @Test
    @DisplayName("题目列表：size 超过 100 → 限制为 100")
    void should_limitSizeTo100_when_sizeExceeds100() {
        // 修复 IMP-4：size 限制测试
        Page<QuestionBank> page = new Page<>(1, 100);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);

        when(questionBankMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);

        PageResult<QuestionListVO> result = adminListQueryService.getQuestionList(
                null, null, null, null, null, null, null, 1, 500);

        // 验证 size 被限制为 100
        assertEquals(100, result.getSize());
    }

    @Test
    @DisplayName("先修关系列表：有效筛选 → 返回列表")
    void should_returnDependencyList_when_validFilters() {
        KnowledgePointDependency dep = new KnowledgePointDependency();
        dep.setDependencyId(1L);
        dep.setKnowledgePointId("kp_001");
        dep.setPrerequisiteId("kp_002");
        dep.setDependencyType("hard");
        dep.setCreateTime(LocalDateTime.now());

        Page<KnowledgePointDependency> page = new Page<>(1, 20);
        page.setRecords(Arrays.asList(dep));
        page.setTotal(1);

        when(dependencyMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);
        when(knowledgePointMapper.selectNamesByIds(anyList(), eq(TENANT_ID)))
                .thenReturn(List.of(
                        Map.of("knowledge_point_id", "kp_001", "name", "知识点A"),
                        Map.of("knowledge_point_id", "kp_002", "name", "知识点B")));

        PageResult<DependencyListVO> result = adminListQueryService.getDependencyList(
                null, null, null, null, null, 1, 20);

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("知识点A", result.getRecords().get(0).getKnowledgePointName());
        assertEquals("知识点B", result.getRecords().get(0).getPrerequisiteName());
    }

    @Test
    @DisplayName("课程标准列表：subject 为 String → 直接取值")
    void should_returnStandardList_when_validFilters() {
        // 修复 IMP-5：subject 是 String 类型
        CurriculumStandard s = new CurriculumStandard();
        s.setStandardId(1L);
        s.setCode("MATH-001");
        s.setDescription("描述");
        s.setStage("高中");
        s.setSubject("数学");
        s.setCreateTime(LocalDateTime.now());

        Page<CurriculumStandard> page = new Page<>(1, 20);
        page.setRecords(Arrays.asList(s));
        page.setTotal(1);

        when(standardMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);

        PageResult<StandardListVO> result = adminListQueryService.getStandardList(
                null, null, null, 1, 20);

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        // 修复 IMP-5：subjectName 直接来自 subject 字段
        assertEquals("数学", result.getRecords().get(0).getSubjectName());
    }

    @Test
    @DisplayName("导入日志列表：有效筛选 → 返回列表")
    void should_returnImportLogList_when_validFilters() {
        DataImportLog log = new DataImportLog();
        log.setImportId(1L);
        log.setDataType("knowledge_point");
        log.setFileSource("upload.json");
        log.setSuccessCount(100);
        log.setFailCount(2);
        log.setSkipCount(3);
        log.setImportedAt(LocalDateTime.now());

        Page<DataImportLog> page = new Page<>(1, 20);
        page.setRecords(Arrays.asList(log));
        page.setTotal(1);

        when(importLogMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);

        PageResult<ImportLogListVO> result = adminListQueryService.getImportLogList(
                null, null, null, 1, 20);

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("upload.json", result.getRecords().get(0).getFileSource());
    }

    @Test
    @DisplayName("导入错误记录：有效 importId → 返回列表")
    void should_returnImportErrorList_when_validImportId() {
        DataImportErrorLog err = new DataImportErrorLog();
        err.setErrorId(1L);
        err.setImportId(100L);
        err.setRecordId("rec_001");
        err.setErrorReason("字段缺失");
        err.setCreateTime(LocalDateTime.now());

        Page<DataImportErrorLog> page = new Page<>(1, 20);
        page.setRecords(Arrays.asList(err));
        page.setTotal(1);

        when(importErrorMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);

        PageResult<ImportErrorVO> result = adminListQueryService.getImportErrorList(100L, 1, 20);

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("字段缺失", result.getRecords().get(0).getErrorReason());
    }

    @Test
    @DisplayName("无数据 → 返回空列表")
    void should_returnEmptyList_when_noData() {
        Page<QuestionBank> page = new Page<>(1, 20);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);

        when(questionBankMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(page);

        PageResult<QuestionListVO> result = adminListQueryService.getQuestionList(
                null, null, null, null, null, null, null, 1, 20);

        assertNotNull(result);
        assertTrue(result.getRecords().isEmpty());
    }

    // ==================== 辅助方法 ====================

    private QuestionBank buildQuestion(Long id, String content, String kpId, String difficulty, String type) {
        QuestionBank q = new QuestionBank();
        q.setQuestionId(id);
        q.setContent(content);
        q.setKnowledgePointId(kpId);
        q.setDifficulty(difficulty);
        q.setType(type);
        q.setTenantId(TENANT_ID);
        q.setCreateTime(LocalDateTime.now());
        return q;
    }
}
