package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.dto.AnswerItem;
import com.jiang.vo.AssessmentDetailVO;
import com.jiang.vo.AssessmentHistoryVO;
import com.jiang.vo.AssessmentResultVO;
import com.jiang.vo.AssessmentVO;

import java.util.List;

/**
 * 专项评测 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
public interface AssessmentService {

    /**
     * 创建评测：选知识点 → 抽题 → 写入 assessment_result（status=pending）
     *
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @param subjectId 科目 ID
     * @return 评测概要
     */
    AssessmentVO createAssessment(Long studentId, String tenantId, Long subjectId);

    /**
     * 查看评测详情（含题目内容，不含答案）
     *
     * @param studentId    学生 ID
     * @param tenantId     租户 ID
     * @param assessmentId 评测 ID
     * @return 评测详情
     */
    AssessmentDetailVO getAssessmentDetail(Long studentId, String tenantId, Long assessmentId);

    /**
     * 提交答案：批改 → 更新 assessment_result（status=graded）→ 掌握度更新 → 错题入队
     *
     * @param studentId    学生 ID
     * @param tenantId     租户 ID
     * @param assessmentId 评测 ID
     * @param answers      学生答案列表
     * @return 批改结果
     */
    AssessmentResultVO submitAndGrade(Long studentId, String tenantId,
                                       Long assessmentId, List<AnswerItem> answers);

    /**
     * 评测历史分页查询
     *
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @param page      页码（从 1 开始）
     * @param size      每页条数
     * @return 分页结果
     */
    PageResult<AssessmentHistoryVO> getAssessmentHistory(Long studentId, String tenantId,
                                                          int page, int size);
}
