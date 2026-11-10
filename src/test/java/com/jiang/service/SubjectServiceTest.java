package com.jiang.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.Subject;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.SubjectServiceImpl;
import com.jiang.vo.SubjectVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SubjectService 单元测试
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@ExtendWith(MockitoExtension.class)
class SubjectServiceTest {

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @Mock
    private SubjectMapper subjectMapper;

    @InjectMocks
    private SubjectServiceImpl subjectService;

    private MockedStatic<StudentContext> mockedStudentContext;

    private static final String TEST_TENANT_ID = "default";

    @BeforeEach
    void setUp() {
        mockedStudentContext = mockStatic(StudentContext.class);
        mockedStudentContext.when(StudentContext::getTenantId).thenReturn(TEST_TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
    }

    @Test
    void should_returnSubjectsWithActiveKp_when_kpExists() {
        // 模拟 GROUP BY 聚合查询结果
        Map<String, Object> row1 = new HashMap<>();
        row1.put("subjectName", "数学");
        row1.put("cnt", 5L);
        Map<String, Object> row2 = new HashMap<>();
        row2.put("subjectName", "英语");
        row2.put("cnt", 3L);
        when(knowledgePointMapper.selectMaps(any(QueryWrapper.class))).thenReturn(Arrays.asList(row1, row2));

        // 模拟科目列表
        Subject math = new Subject();
        math.setSubjectId(1L);
        math.setName("数学");
        math.setStage("高中");
        Subject english = new Subject();
        english.setSubjectId(2L);
        english.setName("英语");
        english.setStage("高中");
        Subject physics = new Subject();
        physics.setSubjectId(3L);
        physics.setName("物理");
        physics.setStage("高中");
        when(subjectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(math, english, physics));

        List<SubjectVO> result = subjectService.getAvailableSubjects();

        assertNotNull(result);
        assertEquals(2, result.size());  // 物理没有 active KP，被过滤
        assertEquals("数学", result.get(0).getName());
        assertEquals(5, result.get(0).getActiveKnowledgePointCount());
        assertEquals("英语", result.get(1).getName());
        assertEquals(3, result.get(1).getActiveKnowledgePointCount());
    }

    @Test
    void should_returnEmptyList_when_noSubjectHasActiveKp() {
        when(knowledgePointMapper.selectMaps(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        // 聚合结果为空时 getAvailableSubjects 直接返回空列表，不会查询科目表，
        // 因此不能再 stub subjectMapper.selectList（否则 Mockito 严格模式报 UnnecessaryStubbing）
        List<SubjectVO> result = subjectService.getAvailableSubjects();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
