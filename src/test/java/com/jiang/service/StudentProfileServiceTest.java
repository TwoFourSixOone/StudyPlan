package com.jiang.service;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.mapper.StudentMapper;
import com.jiang.model.entity.Student;
import com.jiang.security.StudentContext;
import com.jiang.service.impl.StudentProfileServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StudentProfileService 单元测试（产品规则 #11）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StudentProfileService 单元测试")
class StudentProfileServiceTest {

    @Mock
    private StudentMapper studentMapper;

    @InjectMocks
    private StudentProfileServiceImpl studentProfileService;

    private MockedStatic<StudentContext> mockedStudentContext;

    private static final Long STUDENT_ID = 1L;
    private static final String TENANT_ID = "default";

    @BeforeEach
    void setUp() {
        mockedStudentContext = mockStatic(StudentContext.class);
        mockedStudentContext.when(StudentContext::getStudentId).thenReturn(STUDENT_ID);
        mockedStudentContext.when(StudentContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        mockedStudentContext.close();
        StudentContext.clear();
    }

    @Test
    @DisplayName("设置年级：写入数字字符串")
    void should_updateGrade_when_valid() {
        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(student);
        when(studentMapper.updateById(any(Student.class))).thenReturn(1);

        studentProfileService.updateGrade(9);

        assertEquals("9", student.getGrade(), "student.grade 是 VARCHAR，应写数字字符串");
        verify(studentMapper).updateById(student);
    }

    @Test
    @DisplayName("设置年级：越界/为空 → GRADE_INVALID，且不查库")
    void should_throwInvalid_when_gradeOutOfRange() {
        for (Integer bad : new Integer[]{null, 0, 13, -1}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> studentProfileService.updateGrade(bad));
            assertEquals(ErrorCodes.GRADE_INVALID.getCode(), ex.getCode());
        }
        verifyNoInteractions(studentMapper);
    }

    @Test
    @DisplayName("设置年级：学生不存在 → STUDENT_NOT_FOUND")
    void should_throwNotFound_when_studentMissing() {
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> studentProfileService.updateGrade(9));
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("设置年级：更新影响 0 行 → STUDENT_NOT_FOUND")
    void should_throwNotFound_when_updateAffectsNoRow() {
        Student student = new Student();
        student.setStudentId(STUDENT_ID);
        when(studentMapper.selectById(STUDENT_ID)).thenReturn(student);
        when(studentMapper.updateById(any(Student.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> studentProfileService.updateGrade(9));
        assertEquals(ErrorCodes.STUDENT_NOT_FOUND.getCode(), ex.getCode());
    }
}
