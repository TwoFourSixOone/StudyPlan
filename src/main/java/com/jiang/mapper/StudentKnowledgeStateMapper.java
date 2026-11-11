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
            "k.name AS kp_name, k.subject AS kp_subject, k.status AS kp_status, " +
            "k.grade_range AS kp_grade_range " +
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
     * 查询科目下全部已启用知识点 + 学生掌握度（含 grade_range）
     *
     * <p>供 SIG-4 / 调整上下文使用：调用方需按学生年级过滤 grade_range，
     * 否则会把其他学段的知识点算进"未覆盖"（联调实测：高三学生被算出 864 个未覆盖）。</p>
     *
     * @return 每行包含 kpId / kpName / gradeRange / masteryScore
     */
    @Select("SELECT kp.knowledge_point_id AS kpId, kp.name AS kpName, " +
            "kp.grade_range AS gradeRange, s.mastery_score AS masteryScore " +
            "FROM knowledge_point kp " +
            "JOIN subject sub ON sub.subject_id = #{subjectId} " +
            "AND sub.name = kp.subject AND sub.tenant_id = #{tenantId} " +
            "LEFT JOIN student_knowledge_state s " +
            "ON s.knowledge_point_id = kp.knowledge_point_id " +
            "AND s.student_id = #{studentId} AND s.tenant_id = #{tenantId} " +
            "WHERE kp.status = 'active' AND kp.tenant_id = #{tenantId}")
    List<Map<String, Object>> selectActiveKpsWithMastery(@Param("studentId") Long studentId,
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

    // ==================== 批量掌握度（Change 2026-11-11，design §7.3） ====================

    /**
     * 批量查询学生在一批知识点上的状态（1 条 SQL，禁止 N+1）
     */
    @Select("<script>" +
            "SELECT state_id, student_id, knowledge_point_id, mastery_score, confidence, source, " +
            "last_assessed_at, version, tenant_id, create_time, update_time " +
            "FROM student_knowledge_state " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} AND knowledge_point_id IN " +
            "<foreach collection='kpIds' item='kpId' open='(' separator=',' close=')'>#{kpId}</foreach>" +
            "</script>")
    List<StudentKnowledgeState> selectByStudentAndKpIds(@Param("studentId") Long studentId,
                                                        @Param("tenantId") String tenantId,
                                                        @Param("kpIds") List<String> kpIds);

    /**
     * 批量创建缺失的掌握度状态（INSERT IGNORE：命中 uk_student_kp 时忽略，随后重查一次）
     */
    @org.apache.ibatis.annotations.Insert("<script>" +
            "INSERT IGNORE INTO student_knowledge_state " +
            "(student_id, knowledge_point_id, mastery_score, confidence, source, last_assessed_at, " +
            "version, tenant_id, create_time, update_time) VALUES " +
            "<foreach collection='states' item='s' separator=','>" +
            "(#{s.studentId}, #{s.knowledgePointId}, #{s.masteryScore}, #{s.confidence}, #{s.source}, " +
            "#{s.lastAssessedAt}, 0, #{s.tenantId}, NOW(), NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsertIgnore(@Param("states") List<StudentKnowledgeState> states);

    /**
     * 批量更新掌握度终值（CASE WHEN，≤100 条/批；同知识点多题已在内存聚合为一次写入）
     */
    @org.apache.ibatis.annotations.Update("<script>" +
            "UPDATE student_knowledge_state SET " +
            "mastery_score = CASE knowledge_point_id " +
            "<foreach collection='states' item='s'>WHEN #{s.knowledgePointId} THEN " +
            "#{s.masteryScore,jdbcType=DECIMAL} </foreach>END, " +
            "confidence = CASE knowledge_point_id " +
            "<foreach collection='states' item='s'>WHEN #{s.knowledgePointId} THEN " +
            "#{s.confidence,jdbcType=DECIMAL} </foreach>END, " +
            "source = #{source}, last_assessed_at = #{lastAssessedAt}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} AND knowledge_point_id IN " +
            "<foreach collection='states' item='s' open='(' separator=',' close=')'>#{s.knowledgePointId}</foreach>" +
            "</script>")
    int batchUpdateMastery(@Param("studentId") Long studentId,
                           @Param("tenantId") String tenantId,
                           @Param("states") List<StudentKnowledgeState> states,
                           @Param("source") String source,
                           @Param("lastAssessedAt") java.time.LocalDateTime lastAssessedAt);
}
