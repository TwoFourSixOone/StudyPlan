package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.dto.AdminMasteryQueryRequest;
import com.jiang.model.enums.ChangeType;
import com.jiang.vo.MasteryChangeLogVO;
import com.jiang.vo.MasteryDashboardVO;
import com.jiang.vo.MasteryDetailVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 掌握度核心业务 Service 接口
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
public interface MasteryService {

    // ===== 写操作（掌握度更新）=====

    /**
     * 根据每日作业提交记录批量更新掌握度。
     * <p>
     * 内部逻辑：归属校验 → 查询 submission detail → 获取题目难度 → 计算 D_norm →
     * 内存缓存同知识点状态 → 逐知识点更新 + 写日志。
     * <p>
     * 同一知识点在同一批次中多次出现时，采用内存 Map 缓存 state，
     * 确保每个知识点只执行一次 DB 更新，防止后写覆盖前写导致增量丢失。
     *
     * @param studentId     学生 ID
     * @param tenantId      租户 ID
     * @param submissionIds 提交记录 ID 列表
     */
    void updateMasteryFromHomework(Long studentId, String tenantId, List<Long> submissionIds);

    /**
     * 根据单次评测/考试结果更新掌握度。
     * <p>
     * 调用方（AssessmentService / ExamService）负责提供已计算好的 D_norm 值。
     *
     * @param studentId       学生 ID
     * @param tenantId        租户 ID
     * @param knowledgePointId 知识点 ID
     * @param changeType      变更类型
     * @param sourceId        来源 ID
     * @param isCorrect       是否答对
     * @param dNorm           难度归一化值
     */
    void updateMasteryFromResult(Long studentId, String tenantId,
                                  String knowledgePointId, ChangeType changeType,
                                  Long sourceId, boolean isCorrect, BigDecimal dNorm);

    // ===== 学生端查询 =====

    /**
     * 获取学生掌握度看板。仅返回 active 知识点。
     *
     * @param subject 科目名称，可选（null 时返回所有科目的 active 知识点）
     * @return 掌握度看板列表
     */
    List<MasteryDashboardVO> getStudentDashboard(String subject);

    /**
     * 获取学生掌握度看板（支持显示停用知识点）。
     * 修复 IMP-14：showDisabled 参数控制是否显示停用知识点
     *
     * @param subject     科目名称
     * @param showDisabled 是否显示停用知识点
     * @return 掌握度看板列表
     */
    List<MasteryDashboardVO> getStudentDashboard(String subject, boolean showDisabled);

    /**
     * 获取学生指定知识点的掌握度变更历史（按时间倒序）。
     *
     * @param knowledgePointId 知识点 ID
     * @param page             页码，从 1 开始
     * @param size             每页条数
     * @return 变更历史分页结果
     */
    PageResult<MasteryChangeLogVO> getStudentChangeHistory(String knowledgePointId, int page, int size);

    // ===== 管理端查询 =====

    /**
     * 管理端掌握度看板查询。支持按学生/知识点/科目筛选，返回含知识点名称和状态。
     *
     * @param request 查询请求
     * @return 掌握度详情分页结果
     */
    PageResult<MasteryDetailVO> getAdminDashboard(AdminMasteryQueryRequest request);

    /**
     * 管理端查看指定学生的知识点掌握度变更历史。
     *
     * @param studentId        学生 ID
     * @param knowledgePointId 知识点 ID
     * @param page             页码，从 1 开始
     * @param size             每页条数
     * @return 变更历史分页结果
     */
    PageResult<MasteryChangeLogVO> getAdminChangeHistory(Long studentId, String knowledgePointId,
                                                          int page, int size);
}
