package com.jiang.service.impl;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.mapper.StudentMapper;
import com.jiang.model.entity.Student;
import com.jiang.security.StudentContext;
import com.jiang.service.StudentProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 学生个人资料服务实现（产品规则 #11）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@Service
@Slf4j
public class StudentProfileServiceImpl implements StudentProfileService {

    @Autowired
    private StudentMapper studentMapper;

    @Override
    public void updateGrade(Integer grade) {
        // 1. 取值校验：DTO 已校验，Service 再兜一层（防止内部调用绕过）
        if (grade == null || grade < 1 || grade > 12) {
            throw new BusinessException(ErrorCodes.GRADE_INVALID);
        }

        // 2. 身份只从 JWT 上下文取
        Long studentId = StudentContext.getStudentId();
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BusinessException(ErrorCodes.STUDENT_NOT_FOUND);
        }

        // 3. student.grade 是 VARCHAR，统一写数字字符串，与 GradeMatcher 的归一化兼容
        student.setGrade(String.valueOf(grade));
        int rows = studentMapper.updateById(student);
        if (rows == 0) {
            throw new BusinessException(ErrorCodes.STUDENT_NOT_FOUND);
        }
        log.info("学生年级已更新: studentId={}, grade={}", studentId, grade);
    }
}
