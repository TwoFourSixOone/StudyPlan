package com.jiang.converter;

import com.jiang.model.entity.Student;
import com.jiang.vo.StudentVO;

/**
 * Student Entity ↔ VO 公共转换器
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
public class StudentConverter {

    /**
     * Student Entity → StudentVO
     * openid 不下发前端（隐私保护）
     */
    public static StudentVO toVO(Student student) {
        if (student == null) return null;
        StudentVO vo = new StudentVO();
        vo.setStudentId(student.getStudentId());
        vo.setNickname(student.getNickname());
        vo.setAvatar(student.getAvatar());
        vo.setGrade(student.getGrade());
        return vo;
    }
}
