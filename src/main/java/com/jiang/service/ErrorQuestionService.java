package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.model.enums.ErrorQuestionSourceType;
import com.jiang.vo.DueErrorQuestionVO;
import com.jiang.vo.ErrorQuestionDetailVO;
import com.jiang.vo.ErrorQuestionStatisticsVO;
import com.jiang.vo.ErrorQuestionVO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 错题管理 Service 接口
 *
 * <p>核心方法：入队/推进/重置/暂停/重新入队/到期查询/列表查询/统计</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
public interface ErrorQuestionService {

    // ===== 核心写操作 =====

    /**
     * 记录错题（答错时调用）
     * <ul>
     *   <li>首次答错：创建 error_question，stage=1，next_due_date=wrongDate+1天</li>
     *   <li>已在队列中：重置 stage=1，更新 last_wrong_date/next_due_date</li>
     *   <li>并发保护：捕获 DuplicateKeyException → 回退到 UPDATE 路径</li>
     * </ul>
     *
     * <p>BLOCK-1 修复：明确由 gradeHomework 调用（见 design §2.4.2）</p>
     * <p>BLOCK-3 修复：sourceType 使用 ErrorQuestionSourceType 枚举</p>
     *
     * @param studentId        学生 ID
     * @param tenantId         租户 ID
     * @param questionId       题目 ID
     * @param knowledgePointId 知识点 ID
     * @param sourceType       来源类型枚举
     * @param sourceId         来源 ID（语义由 sourceType 决定）
     * @param wrongDate        答错日期
     */
    void recordWrongAnswer(Long studentId, String tenantId, Long questionId,
                           String knowledgePointId,
                           ErrorQuestionSourceType sourceType, Long sourceId,
                           LocalDate wrongDate);

    /**
     * 查询到期错题（按知识点分组，每组 ≤ 3 道）
     *
     * <p>条件：status=active AND next_due_date <= targetDate</p>
     * <p>BLOCK-A 修复：每个知识点最多取 3 道（按 next_due_date ASC, error_question_id ASC 排序后截断）</p>
     * <p>IMP-1 修复：返回 DueErrorQuestionVO 而非实体</p>
     * <p>BLOCK-H 修复：按知识点分别查询，每个知识点 LIMIT 3</p>
     * <p>IMP-AB 修复：返回 LinkedHashMap，key 顺序由 ORDER BY knowledge_point_id ASC 决定（字典序）</p>
     * <p>BLOCK-I 修复：N+1 循环不纳入事务；空 List 的知识点不放入 Map</p>
     *
     * @param studentId  学生 ID
     * @param tenantId   租户 ID
     * @param targetDate 目标日期
     * @return 按知识点分组的到期错题 Map（LinkedHashMap，key 为 knowledgePointId，value 非空 List）
     */
    Map<String, List<DueErrorQuestionVO>> getDueErrorQuestions(
            Long studentId, String tenantId, LocalDate targetDate);

    /**
     * 推进错题周期（重做答对时调用）
     * <ul>
     *   <li>stage++ → 更新 next_due_date = correctDate + interval(newStage)</li>
     *   <li>stage > 5 → status=completed（移出队列）</li>
     * </ul>
     *
     * <p>IMP-2 修复：基准日期改为 correctDate（本次答对日期）</p>
     * <p>IMP-A 修复：UPDATE 加 WHERE current_stage = #{currentStage} 并发保护</p>
     *
     * @param studentId   学生 ID
     * @param tenantId    租户 ID
     * @param questionId  题目 ID
     * @param correctDate 答对日期
     */
    void advanceStage(Long studentId, String tenantId, Long questionId,
                      LocalDate correctDate);

    /**
     * 重置错题周期（重做答错时调用）
     * <ul>
     *   <li>stage=1 → next_due_date = wrongDate + 1天</li>
     * </ul>
     *
     * @param studentId  学生 ID
     * @param tenantId   租户 ID
     * @param questionId 题目 ID
     * @param wrongDate  答错日期
     */
    void resetStage(Long studentId, String tenantId, Long questionId, LocalDate wrongDate);

    /**
     * 掌握度联动：暂停/重新入队
     * <ul>
     *   <li>mastery >= 0.8 → status=paused（仅 active 记录参与）</li>
     *   <li>mastery < 0.8 → 原 paused 的记录 status=active（仅 paused 记录参与）</li>
     * </ul>
     *
     * <p>BLOCK-2 修复：明确暂停语义 + 防抖 + stage 保留</p>
     * <p>BLOCK-B 修复：recordWrongAnswer 后 syncPauseStatus 可能立即暂停——预期行为</p>
     *
     * @param studentId        学生 ID
     * @param tenantId         租户 ID
     * @param knowledgePointId 知识点 ID
     * @param masteryScore     掌握度分数
     */
    void syncPauseStatus(Long studentId, String tenantId,
                         String knowledgePointId, BigDecimal masteryScore);

    // ===== 查询操作 =====

    /**
     * 错题列表分页查询
     *
     * <p>IMP-5 修复：使用自定义 JOIN SQL 避免 N+1</p>
     * <p>IMP-F 修复：page < 1 置 1，size > 100 截断至 100</p>
     *
     * @param studentId        学生 ID
     * @param tenantId         租户 ID
     * @param knowledgePointId 知识点 ID（可选筛选）
     * @param status           状态（可选筛选）
     * @param page             页码（从 1 开始）
     * @param size             每页条数
     * @return 分页结果
     */
    PageResult<ErrorQuestionVO> getErrorQuestions(Long studentId, String tenantId,
                                                  String knowledgePointId, String status,
                                                  int page, int size);

    /**
     * 按 ID 查询单条错题详情（含题目选项、正确答案、解析）
     *
     * @param studentId       学生 ID（越权校验）
     * @param tenantId        租户 ID
     * @param errorQuestionId 错题 ID
     * @return 详情 VO
     * @throws com.jiang.common.exception.BusinessException ERROR_QUESTION_NOT_FOUND 不存在或不属于当前学生
     */
    ErrorQuestionDetailVO getErrorQuestionDetail(Long studentId, String tenantId, Long errorQuestionId);

    /**
     * 错题统计
     *
     * <p>BLOCK-C 修复：COALESCE 防 NULL → NPE</p>
     *
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @return 统计 VO
     */
    ErrorQuestionStatisticsVO getStatistics(Long studentId, String tenantId);
}
