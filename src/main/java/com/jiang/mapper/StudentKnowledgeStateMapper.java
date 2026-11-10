package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.StudentKnowledgeState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 学生知识点掌握度状态 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface StudentKnowledgeStateMapper extends BaseMapper<StudentKnowledgeState> {

    /**
     * 查询学生知识点掌握度状态（JOIN knowledge_point）
     * <p>
     * 返回的 StudentKnowledgeState 包含 kpName、kpSubject、kpStatus 三个瞬态字段，
     * 用于看板展示时减少 N+1 查询。
     * <p>
     * 修复 IMP-14：增加 includeDisabled 参数，控制是否包含停用知识点
     *
     * @param studentId      学生 ID
     * @param tenantId       租户 ID
     * @param subject        科目筛选（可为 null）
     * @param includeDisabled 是否包含停用知识点
     * @return 包含知识点信息的状态列表
     */
    @Select("<script>" +
            "SELECT s.state_id, s.student_id, s.knowledge_point_id, s.mastery_score, " +
            "s.confidence, s.source, s.last_assessed_at, s.version, " +
            "s.tenant_id, s.create_time, s.update_time, " +
            "k.name AS kp_name, k.subject AS kp_subject, k.status AS kp_status " +
            "FROM student_knowledge_state s " +
            "JOIN knowledge_point k ON s.knowledge_point_id = k.knowledge_point_id " +
            "AND s.tenant_id = k.tenant_id " +
            "WHERE s.student_id = #{studentId} AND s.tenant_id = #{tenantId} " +
            "<if test='!includeDisabled'> AND k.status = 'active' </if>" +
            "<if test='subject != null'> AND k.subject = #{subject} </if>" +
            "</script>")
    List<StudentKnowledgeState> selectActiveStatesWithKpInfo(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("subject") String subject,
            @Param("includeDisabled") boolean includeDisabled);

    /**
     * 统计学生指定科目下从未学习过的知识点数量（SIG-4 信号检测）
     * <p>
     * 修复 BadSqlGrammar：原 SQL 引用不存在的 learning_goal_knowledge 表。
     * 实际关联链：goal.subject_id → subject.name → knowledge_point.subject（名称关联）；
     * kUncovered 修复 BLOCK-14：LEFT JOIN 状态表后取 mastery_score IS NULL（无记录）或 0（未学）
     *
     * @param studentId 学生 ID
     * @param subjectId 科目 ID（goal 的科目）
     * @param tenantId  租户 ID
     * @return 未覆盖知识点数量
     */
    @Select("SELECT COUNT(*) FROM knowledge_point kp " +
            "JOIN subject sub ON sub.subject_id = #{subjectId} " +
            "AND sub.name = kp.subject AND sub.tenant_id = #{tenantId} " +
            "LEFT JOIN student_knowledge_state s " +
            "ON s.knowledge_point_id = kp.knowledge_point_id " +
            "AND s.student_id = #{studentId} AND s.tenant_id = #{tenantId} " +
            "WHERE kp.status = 'active' AND kp.tenant_id = #{tenantId} " +
            "AND (s.mastery_score IS NULL OR s.mastery_score = 0)")
    long countUncoveredByStudentAndSubject(@Param("studentId") Long studentId,
                                          @Param("subjectId") Long subjectId,
                                          @Param("tenantId") String tenantId);

    /**
     * 查询学生指定科目下未覆盖知识点清单（ID + 名称，计划调整 Agent 上下文）
     * <p>
     * 与 countUncoveredByStudentAndSubject 同一关联链，LIMIT 截断到最近需要覆盖的数量，
     * 避免未覆盖知识点过多（如 269 个）导致 Prompt 膨胀。
     *
     * @param studentId 学生 ID
     * @param subjectId 科目 ID
     * @param tenantId  租户 ID
     * @param limit     截断条数
     * @return 未覆盖知识点 ID + 名称列表
     */
    @Select("SELECT kp.knowledge_point_id AS kpId, kp.name AS kpName " +
            "FROM knowledge_point kp " +
            "JOIN subject sub ON sub.subject_id = #{subjectId} " +
            "AND sub.name = kp.subject AND sub.tenant_id = #{tenantId} " +
            "LEFT JOIN student_knowledge_state s " +
            "ON s.knowledge_point_id = kp.knowledge_point_id " +
            "AND s.student_id = #{studentId} AND s.tenant_id = #{tenantId} " +
            "WHERE kp.status = 'active' AND kp.tenant_id = #{tenantId} " +
            "AND (s.mastery_score IS NULL OR s.mastery_score = 0) " +
            "ORDER BY kp.knowledge_point_id ASC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> selectUncoveredKpList(@Param("studentId") Long studentId,
                                                   @Param("subjectId") Long subjectId,
                                                   @Param("tenantId") String tenantId,
                                                   @Param("limit") int limit);

    /**
     * 按学生和科目查询掌握度状态（用于停用知识点展示规则）
     */
    @Select("SELECT * FROM student_knowledge_state " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId}")
    List<StudentKnowledgeState> selectByStudentAndSubject(@Param("studentId") Long studentId,
                                                           @Param("subject") String subject,
                                                           @Param("tenantId") String tenantId);
}
