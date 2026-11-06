package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.ErrorQuestion;
import com.jiang.model.entity.QuestionBank;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 错题队列 Mapper 接口
 *
 * IMP-5 修复：新增 selectPageWithDetail 自定义 JOIN SQL，避免 N+1
 * IMP-6 修复：selectStatistics 返回 Map，Service 层转为 VO
 * BLOCK-C 修复：selectStatistics 使用 COALESCE 防 NULL
 * BLOCK-H 修复：原 selectDueQuestions 拆分为 selectDueKnowledgePointIds + selectDueQuestionsByKnowledgePoint
 * IMP-G 修复：selectPageWithDetail 使用 COALESCE 兜底 questionContent
 * IMP-I 修复：Mapper 方法注释引用索引名
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Mapper
public interface ErrorQuestionMapper extends BaseMapper<ErrorQuestion> {

    /**
     * BLOCK-H 修复：查询有到期错题的知识点 ID 列表（去重）
     * 走 idx_student_status_due 索引，返回 knowledge_point_id 列表
     * IMP-AH 修复：LIMIT 51 作为知识点数量上限保护，Service 层 size > 50 时 WARN + subList(0, 50)
     */
    @Select("SELECT DISTINCT knowledge_point_id FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND status = 'active' AND next_due_date <= #{targetDate} " +
            "ORDER BY knowledge_point_id ASC " +
            "LIMIT 51")
    List<String> selectDueKnowledgePointIds(@Param("studentId") Long studentId,
                                             @Param("tenantId") String tenantId,
                                             @Param("targetDate") LocalDate targetDate);

    /**
     * BLOCK-H 修复：按知识点查询到期错题前 3 道
     * IMP-D: 排序 next_due_date ASC, error_question_id ASC 保证截断稳定性
     * 索引命中：idx_student_status_due (student_id, tenant_id, status, next_due_date)
     */
    @Select("SELECT error_question_id, student_id, question_id, knowledge_point_id, " +
            "source_type, source_id, first_wrong_date, last_wrong_date, " +
            "current_stage, next_due_date, status, completed_date, " +
            "tenant_id, create_time, update_time " +
            "FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId} " +
            "AND status = 'active' AND next_due_date <= #{targetDate} " +
            "ORDER BY next_due_date ASC, error_question_id ASC " +
            "LIMIT 3")
    List<ErrorQuestion> selectDueQuestionsByKnowledgePoint(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("knowledgePointId") String knowledgePointId,
            @Param("targetDate") LocalDate targetDate);

    /**
     * 分页查询错题列表（JOIN knowledge_point + question_bank，避免 N+1）
     * IMP-5 修复
     * IMP-G: COALESCE 兜底 questionContent
     * IMP-N 修复：排序改为 first_wrong_date DESC, error_question_id DESC
     *   （避免 syncPauseStatus 的 update_time=NOW() 导致分页记录跳动）
     *   BLOCK-F 修复：first_wrong_date 只在 INSERT 时写入，UPDATE 不修改，排序键稳定
     * IMP-U 说明：该查询 ORDER BY first_wrong_date 无法被现有索引覆盖，会触发 filesort；
     *   当前数据量可接受，登记 TD-PAGINATION-FILESORT
     * 索引命中：idx_student_kp_status (student_id, tenant_id, knowledge_point_id, status) — WHERE 筛选
     */
    @Select("<script>" +
            "SELECT eq.error_question_id, eq.question_id, eq.knowledge_point_id, " +
            "kp.name AS kp_name, eq.source_type, eq.first_wrong_date, eq.last_wrong_date, " +
            "eq.current_stage, eq.next_due_date, eq.status, " +
            "COALESCE(qb.content, '[题目已删除]') AS question_content, " +
            "COALESCE(qb.difficulty, 'unknown') AS question_difficulty " +
            "FROM error_question eq " +
            "LEFT JOIN knowledge_point kp ON eq.knowledge_point_id = kp.knowledge_point_id " +
            "  AND kp.tenant_id = eq.tenant_id " +
            "LEFT JOIN question_bank qb ON eq.question_id = qb.question_id " +
            "  AND qb.tenant_id = eq.tenant_id " +
            "WHERE eq.student_id = #{studentId} AND eq.tenant_id = #{tenantId} " +
            "<if test='knowledgePointId != null'> AND eq.knowledge_point_id = #{knowledgePointId} </if>" +
            "<if test='status != null'> AND eq.status = #{status} </if>" +
            "ORDER BY eq.first_wrong_date DESC, eq.error_question_id DESC " +
            "LIMIT #{offset}, #{size}" +
            "</script>")
    List<Map<String, Object>> selectPageWithDetail(@Param("studentId") Long studentId,
                                                    @Param("tenantId") String tenantId,
                                                    @Param("knowledgePointId") String knowledgePointId,
                                                    @Param("status") String status,
                                                    @Param("offset") int offset,
                                                    @Param("size") int size);

    /**
     * 统计总数（配合分页）
     * 索引命中：idx_student_kp_status (student_id, tenant_id, knowledge_point_id, status)
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "<if test='knowledgePointId != null'> AND knowledge_point_id = #{knowledgePointId} </if>" +
            "<if test='status != null'> AND status = #{status} </if>" +
            "</script>")
    int selectCount(@Param("studentId") Long studentId,
                    @Param("tenantId") String tenantId,
                    @Param("knowledgePointId") String knowledgePointId,
                    @Param("status") String status);

    /**
     * 按知识点批量暂停
     * IMP-7 修复：WHERE 条件明确 status='active'，仅活跃记录参与
     * IMP-R 修复：不更新 update_time（暂停/激活是派生状态变更，不视为内容修改）
     * 索引命中：idx_student_kp_status
     */
    @Update("UPDATE error_question SET status = 'paused' " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId} AND status = 'active'")
    int batchPauseByKnowledgePoint(@Param("studentId") Long studentId,
                                    @Param("tenantId") String tenantId,
                                    @Param("knowledgePointId") String knowledgePointId);

    /**
     * 按知识点批量重新激活
     * IMP-7 修复：WHERE 条件明确 status='paused'，仅暂停记录参与
     * IMP-R 修复：不更新 update_time（同上）
     */
    @Update("UPDATE error_question SET status = 'active' " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId} AND status = 'paused'")
    int batchReactivateByKnowledgePoint(@Param("studentId") Long studentId,
                                         @Param("tenantId") String tenantId,
                                         @Param("knowledgePointId") String knowledgePointId);

    /**
     * 错题统计
     * BLOCK-C 修复：COALESCE 防 SUM 空集返回 NULL → NPE
     * IMP-6 修复：返回 Map，Service 层转 VO
     */
    @Select("SELECT " +
            "COALESCE(COUNT(*), 0) AS totalCount, " +
            "COALESCE(SUM(CASE WHEN status = 'active' AND next_due_date <= #{today} THEN 1 ELSE 0 END), 0) AS dueCount, " +
            "COALESCE(SUM(CASE WHEN status = 'paused' THEN 1 ELSE 0 END), 0) AS pausedCount " +
            "FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND status != 'completed'")
    Map<String, Object> selectStatistics(@Param("studentId") Long studentId,
                                          @Param("tenantId") String tenantId,
                                          @Param("today") LocalDate today);

    /**
     * BLOCK-E 修复：advanceStage 并发保护 UPDATE
     * IMP-K 说明：手写 SQL 不走 MyBatis-Plus 自动填充，update_time 显式 NOW()；
     *   ErrorQuestion 不使用乐观锁 version（理由：状态机校验已提供并发保护）。
     * WHERE current_stage = #{currentStage}（数据库旧值） AND status != 'completed'
     */
    @Update("UPDATE error_question SET current_stage = #{newStage}, " +
            "next_due_date = #{nextDueDate}, update_time = NOW() " +
            "WHERE error_question_id = #{errorQuestionId} AND tenant_id = #{tenantId} " +
            "AND current_stage = #{currentStage} AND status != 'completed'")
    int advanceStageWithCheck(@Param("errorQuestionId") Long errorQuestionId,
                               @Param("tenantId") String tenantId,
                               @Param("currentStage") int currentStage,
                               @Param("newStage") int newStage,
                               @Param("nextDueDate") LocalDate nextDueDate);

    /**
     * BLOCK-E 修复：advanceStage 完成态 UPDATE
     * IMP-K 说明：同 advanceStageWithCheck，手写 SQL 显式 NOW()，不走自动填充。
     * WHERE current_stage = #{currentStage}（数据库旧值） AND status != 'completed'
     */
    @Update("UPDATE error_question SET current_stage = #{newStage}, " +
            "status = 'completed', completed_date = #{completedDate}, " +
            "next_due_date = NULL, update_time = NOW() " +
            "WHERE error_question_id = #{errorQuestionId} AND tenant_id = #{tenantId} " +
            "AND current_stage = #{currentStage} AND status != 'completed'")
    int completeStageWithCheck(@Param("errorQuestionId") Long errorQuestionId,
                                @Param("tenantId") String tenantId,
                                @Param("currentStage") int currentStage,
                                @Param("newStage") int newStage,
                                @Param("completedDate") LocalDate completedDate);

    /**
     * 获取到期错题对应的原题内容（JOIN question_bank）
     * 用于构造举一反三 Agent 的输入
     * IMP-5 修正：源题选择策略——取最早到期的 1 道错题对应的原题，
     *   作为该"代表错题"。不要求覆盖所有错题，
     *   因为举一反三的目标是"巩固知识点"而非"覆盖所有错题"。
     * Change: 2026-11-03_similar_question_generation
     */
    @Select("SELECT qb.question_id, qb.content, qb.answer, qb.analysis, " +
            "qb.difficulty, qb.type, qb.knowledge_point_id " +
            "FROM error_question eq " +
            "JOIN question_bank qb ON eq.question_id = qb.question_id " +
            "  AND qb.tenant_id = eq.tenant_id " +
            "WHERE eq.student_id = #{studentId} AND eq.tenant_id = #{tenantId} " +
            "AND eq.knowledge_point_id = #{kpId} " +
            "AND eq.status = 'active' AND eq.next_due_date <= #{targetDate} " +
            "ORDER BY eq.next_due_date ASC, eq.error_question_id ASC " +
            "LIMIT 1")
    QuestionBank selectSourceQuestionForSimilar(@Param("studentId") Long studentId,
                                                @Param("tenantId") String tenantId,
                                                @Param("kpId") String kpId,
                                                @Param("targetDate") LocalDate targetDate);

    /**
     * 统计每个知识点的活跃错题数量（用于配额计算 kpErrorCountMap）
     * Change: 2026-11-03_similar_question_generation
     *
     * @return List of Map，每个 Map 包含 knowledge_point_id 和 error_count
     */
    @Select("SELECT knowledge_point_id, COUNT(*) AS error_count FROM error_question " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} AND status = 'active' " +
            "GROUP BY knowledge_point_id")
    List<Map<String, Object>> selectActiveErrorCountGroupByKp(@Param("studentId") Long studentId,
                                                               @Param("tenantId") String tenantId);

    /**
     * 查询超期未解决的错题（T-08：错题超 30 天标记）
     * 查询 stage=5 且 first_wrong_date < cutoffDate 的错题
     * 修复 H-1：增加 tenant_id 隔离
     *
     * @param cutoffDate 截止日期（30 天前）
     * @param tenantId   租户 ID
     * @return 超期错题列表
     */
    @Select("SELECT error_question_id, question_id, knowledge_point_id, student_id, " +
            "first_wrong_date, current_stage, status " +
            "FROM error_question " +
            "WHERE current_stage >= 5 " +
            "AND first_wrong_date < #{cutoffDate} " +
            "AND status = 'active' " +
            "AND tenant_id = #{tenantId}")
    List<ErrorQuestion> selectCriticalErrors(@Param("cutoffDate") LocalDate cutoffDate,
                                              @Param("tenantId") String tenantId);
}
