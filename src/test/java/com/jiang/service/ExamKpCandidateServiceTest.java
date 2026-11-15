package com.jiang.service;

import com.jiang.agent.AgentDegradationLogger;
import com.jiang.dto.KpCandidate;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.entity.Subject;
import com.jiang.service.impl.ExamKpCandidateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识点候选构造与缓存测试（design v4 §3.7 / tasks E1-E5）
 *
 * <p>覆盖：年级过滤 / 排序（精确年级 → 有掌握度 → kpId）/ 双上限 / 截断率与降级日志 /
 * attribution 切片含被 parse 截断的 kp（可达性）/ 缓存 / Prompt 渲染。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@DisplayName("ExamKpCandidateService 候选构造")
class ExamKpCandidateServiceTest {

    private static final String TENANT = "default";
    private static final Long SUBJECT_ID = 10L;
    private static final Long STUDENT_ID = 1L;
    private static final String SUBJECT_NAME = "数学";

    private KnowledgePointMapper knowledgePointMapper;
    private StudentKnowledgeStateMapper stateMapper;
    private SubjectMapper subjectMapper;
    private AgentDegradationLogger degradationLogger;
    private ExamKpCandidateServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgePointMapper = mock(KnowledgePointMapper.class);
        stateMapper = mock(StudentKnowledgeStateMapper.class);
        subjectMapper = mock(SubjectMapper.class);
        degradationLogger = mock(AgentDegradationLogger.class);
        service = new ExamKpCandidateServiceImpl();
        ReflectionTestUtils.setField(service, "knowledgePointMapper", knowledgePointMapper);
        ReflectionTestUtils.setField(service, "stateMapper", stateMapper);
        ReflectionTestUtils.setField(service, "subjectMapper", subjectMapper);
        ReflectionTestUtils.setField(service, "degradationLogger", degradationLogger);
        ReflectionTestUtils.setField(service, "parseMax", 300);
        ReflectionTestUtils.setField(service, "attributionMax", 500);
        ReflectionTestUtils.setField(service, "ttlSeconds", 600L);

        Subject subject = new Subject();
        subject.setSubjectId(SUBJECT_ID);
        subject.setName(SUBJECT_NAME);
        when(subjectMapper.selectByIdAndTenant(eq(SUBJECT_ID), eq(TENANT))).thenReturn(subject);
    }

    private KnowledgePoint kp(String id, String name, String gradeRange) {
        KnowledgePoint point = new KnowledgePoint();
        point.setKnowledgePointId(id);
        point.setName(name);
        point.setGradeRange(gradeRange);
        point.setSubject(SUBJECT_NAME);
        point.setStatus("active");
        return point;
    }

    private List<KnowledgePoint> manyKps(int count) {
        List<KnowledgePoint> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(kp("kp_" + String.format("%04d", i), "知识点" + i, null));
        }
        return list;
    }

    @Test
    @DisplayName("年级过滤（同段 ≤ 学生年级）：高一可含初中？否；10-12 对高一不适用")
    void should_filterByGrade_andSort() {
        when(knowledgePointMapper.selectActiveBySubject(TENANT, SUBJECT_NAME)).thenReturn(List.of(
                kp("kp_3", "跨年级数列", "10-12"),
                kp("kp_1", "全学段", null),
                kp("kp_2", "初中", "7-9"),
                kp("kp_4", "高一", "10")));
        StudentKnowledgeState state = new StudentKnowledgeState();
        state.setKnowledgePointId("kp_1");
        when(stateMapper.selectByStudentAndKpIds(eq(STUDENT_ID), eq(TENANT), anyList()))
                .thenReturn(List.of(state));

        ExamKpCandidateService.CandidateSnapshot snapshot =
                service.build(TENANT, SUBJECT_ID, STUDENT_ID, 10);

        List<String> ids = snapshot.getParseCandidates().stream()
                .map(KpCandidate::knowledgePointId).toList();
        // 精确年级（kp_4 "10"）优先；其余按"有掌握度（kp_1）→ kpId 升序"；
        // 初中 kp_2 不同学段被过滤；kp_3 "10-12" 含高二/高三，对高一不适用（2026-11-12 新口径）
        assertEquals(List.of("kp_4", "kp_1"), ids);
        assertEquals(2, snapshot.getKpTotal());
        assertEquals(0, snapshot.getKpTruncated());
    }

    @Test
    @DisplayName("新口径：高三包含高一/高二与本段 10-12，不包含初中/小学")
    void should_includeLowerGradesInSameStage() {
        when(knowledgePointMapper.selectActiveBySubject(TENANT, SUBJECT_NAME)).thenReturn(List.of(
                kp("kp_h10", "高一", "10"),
                kp("kp_h11", "高二", "11"),
                kp("kp_h12", "高三", "12"),
                kp("kp_hall", "高中通用", "10-12"),
                kp("kp_j9", "初三", "9"),
                kp("kp_j", "初中", "7-9"),
                kp("kp_p", "小学", "3")));
        when(stateMapper.selectByStudentAndKpIds(any(), any(), anyList())).thenReturn(List.of());

        ExamKpCandidateService.CandidateSnapshot snapshot =
                service.build(TENANT, SUBJECT_ID, STUDENT_ID, 12);

        List<String> ids = snapshot.getParseCandidates().stream()
                .map(KpCandidate::knowledgePointId).toList();
        assertTrue(ids.contains("kp_h10"), "高一知识（集合/复数等高考考点）应可归因");
        assertTrue(ids.contains("kp_h11"));
        assertTrue(ids.contains("kp_h12"));
        assertTrue(ids.contains("kp_hall"));
        assertFalse(ids.contains("kp_j9"), "高三候选不应出现初中知识");
        assertFalse(ids.contains("kp_j"));
        assertFalse(ids.contains("kp_p"), "高三候选不应出现小学知识");
    }

    @Test
    @DisplayName("学生年级为空 → 不做年级过滤")
    void should_notFilter_when_studentGradeNull() {
        when(knowledgePointMapper.selectActiveBySubject(TENANT, SUBJECT_NAME)).thenReturn(List.of(
                kp("kp_1", "初中", "7-9"),
                kp("kp_2", "高中", "10-12")));
        when(stateMapper.selectByStudentAndKpIds(any(), any(), anyList())).thenReturn(List.of());

        ExamKpCandidateService.CandidateSnapshot snapshot = service.build(TENANT, SUBJECT_ID, null, null);

        assertEquals(2, snapshot.getKpTotal());
    }

    @Test
    @DisplayName("双上限：parse 150 / attribution 500，attribution 切片含被 parse 截断的 kp（可达性）")
    void should_applyTieredLimits_andKeepTruncatedKpForAttribution() {
        when(knowledgePointMapper.selectActiveBySubject(TENANT, SUBJECT_NAME)).thenReturn(manyKps(600));
        when(stateMapper.selectByStudentAndKpIds(any(), any(), anyList())).thenReturn(List.of());

        ExamKpCandidateService.CandidateSnapshot snapshot =
                service.build(TENANT, SUBJECT_ID, STUDENT_ID, 10);

        assertEquals(300, snapshot.getParseCandidates().size());
        assertEquals(500, snapshot.getAttributionCandidates().size());
        assertEquals(600, snapshot.getKpTotal());
        assertEquals(300, snapshot.getKpTruncated());
        assertEquals(0.5d, snapshot.getTruncationRate(), 0.0001);

        // 可达性：被 parse 截断的第 301 个 kp 仍出现在 attribution 切片
        String truncatedKpId = snapshot.getAttributionCandidates().get(300).knowledgePointId();
        assertTrue(snapshot.getAttributionCandidates().stream()
                .anyMatch(c -> c.knowledgePointId().equals(truncatedKpId)));
        assertFalse(snapshot.getParseCandidates().stream()
                .anyMatch(c -> c.knowledgePointId().equals(truncatedKpId)));
        assertTrue(snapshot.isAllowed(truncatedKpId), "兜底清单内的 kp 应被白名单接受");

        verify(degradationLogger).log(anyString(), anyString(), anyString(),
                eq("truncate_kp_candidates"), eq(TENANT));
    }

    @Test
    @DisplayName("候选 >1000 → 记 kp_unreachable 降级日志（兜底可能不可达的边界）")
    void should_logKpUnreachable_when_over1000() {
        when(knowledgePointMapper.selectActiveBySubject(TENANT, SUBJECT_NAME)).thenReturn(manyKps(1001));
        when(stateMapper.selectByStudentAndKpIds(any(), any(), anyList())).thenReturn(List.of());

        service.build(TENANT, SUBJECT_ID, STUDENT_ID, 10);

        verify(degradationLogger).log(anyString(), anyString(), anyString(), eq("kp_unreachable"), eq(TENANT));
    }

    @Test
    @DisplayName("缓存：同一学生/科目/年级 600s 内复用，不重复查库")
    void should_cacheSnapshot() {
        when(knowledgePointMapper.selectActiveBySubject(TENANT, SUBJECT_NAME))
                .thenReturn(List.of(kp("kp_1", "全学段", null)));
        when(stateMapper.selectByStudentAndKpIds(any(), any(), anyList())).thenReturn(List.of());

        service.build(TENANT, SUBJECT_ID, STUDENT_ID, 10);
        service.build(TENANT, SUBJECT_ID, STUDENT_ID, 10);

        verify(knowledgePointMapper, times(1)).selectActiveBySubject(TENANT, SUBJECT_NAME);

        service.clearCache();
        service.build(TENANT, SUBJECT_ID, STUDENT_ID, 10);
        verify(knowledgePointMapper, times(2)).selectActiveBySubject(TENANT, SUBJECT_NAME);
    }

    @Test
    @DisplayName("科目不存在 → 空快照（不抛异常，降级为全待人工归因）")
    void should_returnEmpty_when_subjectMissing() {
        when(subjectMapper.selectByIdAndTenant(eq(999L), eq(TENANT))).thenReturn(null);

        ExamKpCandidateService.CandidateSnapshot snapshot =
                service.build(TENANT, 999L, STUDENT_ID, 10);

        assertTrue(snapshot.getParseCandidates().isEmpty());
        assertTrue(snapshot.getAttributionCandidates().isEmpty());
        assertEquals(0, snapshot.getKpTotal());
        verify(knowledgePointMapper, never()).selectActiveBySubject(anyString(), anyString());
    }

    @Test
    @DisplayName("renderForPrompt：- ID | 名称 紧凑格式；空清单固定文案")
    void should_renderPromptBlock() {
        String text = service.renderForPrompt(List.of(
                new KpCandidate("kp-1", "集合与函数"),
                new KpCandidate("kp-2", "导数")));

        assertEquals("- kp-1 | 集合与函数\n- kp-2 | 导数", text);
        assertTrue(service.renderForPrompt(List.of()).contains("本卷无候选知识点"));
    }

    @Test
    @DisplayName("A7：科目按 subjectId+tenantId 定位（跨租户同名科目不命中，不按名称裸查）")
    void should_resolveSubject_withTenant() {
        when(subjectMapper.selectByIdAndTenant(eq(SUBJECT_ID), eq("other-tenant"))).thenReturn(null);

        ExamKpCandidateService.CandidateSnapshot snapshot =
                service.build("other-tenant", SUBJECT_ID, STUDENT_ID, 10);

        assertTrue(snapshot.getParseCandidates().isEmpty());
        assertTrue(snapshot.getAttributionCandidates().isEmpty());
        verify(subjectMapper).selectByIdAndTenant(eq(SUBJECT_ID), eq("other-tenant"));
        verify(knowledgePointMapper, never()).selectActiveBySubject(anyString(), anyString());
    }
}
