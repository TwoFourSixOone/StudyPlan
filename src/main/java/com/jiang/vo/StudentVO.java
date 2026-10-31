package com.jiang.vo;

import lombok.Data;

/**
 * 学生信息 VO
 * openid 不下发前端（隐私保护）
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Data
public class StudentVO {

    private Long studentId;
    private String nickname;
    private String avatar;
    private String grade;
}
