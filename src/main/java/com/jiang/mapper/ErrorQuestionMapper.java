package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.ErrorQuestion;
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
}
