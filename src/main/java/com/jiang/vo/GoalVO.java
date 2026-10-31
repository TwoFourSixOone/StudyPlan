package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习目标视图对象
 *
 * <p>安全断言（N3）：学生端所有查询接口均强制 studentId = StudentContext.getStudentId() 过滤 + 所有权校验，
 * 因此学生端返回的 GoalVO.studentId 恒为当前登录学生，不存在越权泄露他人 studentId 的可能。
 * 管理端返回实际学生 ID，用于管理员查看不同学生的目标。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalVO {

    private Long goalId;

    private Long studentId;          // 学生端恒等于当前登录学生；管理端返回实际学生 ID

    private Long subjectId;

    private String subjectName;      // 关联查询科目名称

    private String direction;

    private Integer targetScore;

    private LocalDate startDate;

    private LocalDate endDate;

    private String status;           // active / completed / expired

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
