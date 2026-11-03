package com.jiang.service;

import com.jiang.dto.SubmitAnswerRequest;
import com.jiang.vo.GradeResultVO;
import com.jiang.vo.HomeworkVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 每日作业 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
public interface HomeworkService {

    /**
     * 生成每日作业
     * NEW-3: 新增 subjectId 参数，用于调用 GoalService.getActiveGoal(subjectId)
     *
     * @param studentId 学生 ID（来自 StudentContext）
     * @param tenantId  租户 ID（来自 StudentContext）
     * @param subjectId 科目 ID（来自请求参数）
     * @param planDate  计划日期
     */
    void generateDailyHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate);

    /**
     * 查询指定日期作业列表
     * IMP-10: 接口名改为 by-date，避免 today 语义误导
     * NEW-3: 新增 subjectId 参数
     *
     * @param studentId 学生 ID（来自 StudentContext）
     * @param tenantId  租户 ID（来自 StudentContext）
     * @param subjectId 科目 ID（来自请求参数）
     * @param planDate  计划日期
     * @return 作业列表
     */
    List<HomeworkVO> getHomeworkByDate(Long studentId, String tenantId, Long subjectId, LocalDate planDate);

    /**
     * 提交单题答案
     * NEW-4: 接口签名包含 studentId/tenantId，与 Controller 调用对齐
     *
     * @param studentId 学生 ID（来自 StudentContext）
     * @param tenantId  租户 ID（来自 StudentContext）
     * @param request   提交答案请求
     */
    void submitAnswer(Long studentId, String tenantId, SubmitAnswerRequest request);

    /**
     * 批改作业
     * OBS-1: 新增 subjectId 参数
     *
     * @param studentId 学生 ID（来自 StudentContext）
     * @param tenantId  租户 ID（来自 StudentContext）
     * @param subjectId 科目 ID（来自请求参数）
     * @param planDate  计划日期
     * @return 批改结果
     */
    GradeResultVO gradeHomework(Long studentId, String tenantId, Long subjectId, LocalDate planDate);
}
