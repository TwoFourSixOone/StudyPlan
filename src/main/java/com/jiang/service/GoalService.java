package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.dto.CreateGoalRequest;
import com.jiang.dto.UpdateGoalRequest;
import com.jiang.vo.GoalVO;

/**
 * 学习目标管理 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
public interface GoalService {

    /** 创建学习目标 */
    GoalVO createGoal(CreateGoalRequest request);

    /** 查看当前活跃目标（学生端，按 studentId + 可选 subjectId 查询） */
    GoalVO getActiveGoal(Long subjectId);

    /** 查看目标列表（学生端，含历史，分页） */
    PageResult<GoalVO> getGoalList(int page, int size);

    /** 查看目标详情（学生端，校验所有权） */
    GoalVO getGoalDetail(Long goalId);

    /** 修改目标 */
    GoalVO updateGoal(Long goalId, UpdateGoalRequest request);

    /** 结束目标 */
    void completeGoal(Long goalId);

    /** 查询目标列表（管理端，筛选+分页） */
    PageResult<GoalVO> adminGetGoalList(Long studentId, Long subjectId, String status, int page, int size);

    /** 查看目标详情（管理端） */
    GoalVO adminGetGoalDetail(Long goalId);
}
