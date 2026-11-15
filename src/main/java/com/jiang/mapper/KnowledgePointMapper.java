package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.KnowledgePoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 知识点 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface KnowledgePointMapper extends BaseMapper<KnowledgePoint> {

    /**
     * 带租户隔离的查询（IMP-27：id 参数为 String）
     */
    @Select("SELECT * FROM knowledge_point WHERE knowledge_point_id = #{id} AND tenant_id = #{tenantId}")
    KnowledgePoint selectByIdWithTenant(@Param("id") String id, @Param("tenantId") String tenantId);

    /**
     * 修复 IMP-13：selectOverdue 查询 pending_review 且 create_time < overdueDate
     */
    @Select("SELECT * FROM knowledge_point WHERE status = 'pending_review' " +
            "AND create_time < #{overdueDate} AND tenant_id = #{tenantId}")
    List<KnowledgePoint> selectOverdue(@Param("overdueDate") LocalDate overdueDate,
                                        @Param("tenantId") String tenantId);

    /**
     * 修复 IMP-5：批量查询知识点名称，避免 N+1
     * knowledge_point_id 是 String 类型
     */
    @Select("<script>" +
            "SELECT knowledge_point_id, name FROM knowledge_point WHERE tenant_id = #{tenantId} AND knowledge_point_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<Map<String, Object>> selectNamesByIds(@Param("ids") List<String> ids,
                                                @Param("tenantId") String tenantId);

    /**
     * 批量查询知识点名称 + 学生当前掌握度（试卷诊断聚合用，1 条 SQL 避免 N+1）
     *
     * <p>LEFT JOIN {@code student_knowledge_state} 命中 {@code uk_student_kp}
     * （student_id + knowledge_point_id + tenant_id 唯一），单知识点至多匹配 1 行，不发散行数；
     * 学生未评测该知识点时 {@code masteryScore} 为 NULL。显式列名（禁 {@code select *}），
     * Map 键为列别名：{@code knowledgePointId / name / masteryScore}。</p>
     *
     * @param ids       知识点 ID 列表（调用方保证非空）
     * @param tenantId  租户 ID
     * @param studentId 学生 ID
     * @return Map 行列表（knowledgePointId / name / masteryScore）
     */
    @Select("<script>" +
            "SELECT kp.knowledge_point_id AS knowledgePointId, kp.name AS name, " +
            "sks.mastery_score AS masteryScore " +
            "FROM knowledge_point kp " +
            "LEFT JOIN student_knowledge_state sks ON sks.student_id = #{studentId} " +
            "AND sks.knowledge_point_id = kp.knowledge_point_id AND sks.tenant_id = #{tenantId} " +
            "WHERE kp.tenant_id = #{tenantId} AND kp.knowledge_point_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<Map<String, Object>> selectNamesWithMasteryByIds(@Param("ids") List<String> ids,
                                                           @Param("tenantId") String tenantId,
                                                           @Param("studentId") Long studentId);

    /**
     * 批量查询知识点的 subject 和 standards（用于题目列表关联学科和学段）
     * 返回 Map 结构：knowledge_point_id, subject, standards
     */
    @Select("<script>" +
            "SELECT knowledge_point_id, subject, standards, grade_range FROM knowledge_point WHERE tenant_id = #{tenantId} AND knowledge_point_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<Map<String, Object>> selectSubjectAndStandardsByIds(@Param("ids") List<String> ids,
                                                              @Param("tenantId") String tenantId);

    /**
     * 查询科目下全部已启用知识点（v4 候选构造用；显式列名，禁 select *）
     *
     * <p>年级过滤、排序与上限由 {@code ExamKpCandidateService} 在内存完成（零 DDL）。</p>
     */
    @Select("SELECT knowledge_point_id, name, grade_range FROM knowledge_point " +
            "WHERE tenant_id = #{tenantId} AND subject = #{subject} AND status = 'active'")
    List<KnowledgePoint> selectActiveBySubject(@Param("tenantId") String tenantId,
                                                @Param("subject") String subject);

    /**
     * 根据学科查询知识点 ID 列表
     */
    @Select("SELECT knowledge_point_id FROM knowledge_point WHERE tenant_id = #{tenantId} AND subject = #{subject}")
    List<String> selectIdsBySubject(@Param("subject") String subject, @Param("tenantId") String tenantId);

    /**
     * 根据学科和学段查询知识点 ID 列表
     * 通过 standards JSON 关联 curriculum_standard 表获取学段
     */
    @Select("<script>" +
            "SELECT kp.knowledge_point_id FROM knowledge_point kp " +
            "INNER JOIN curriculum_standard cs ON JSON_CONTAINS(kp.standards, JSON_OBJECT('id', cs.code)) " +
            "WHERE kp.tenant_id = #{tenantId} AND cs.tenant_id = #{tenantId} " +
            "AND kp.subject = #{subject} AND cs.stage = #{stage}" +
            "</script>")
    List<String> selectIdsBySubjectAndStage(@Param("subject") String subject,
                                             @Param("stage") String stage,
                                             @Param("tenantId") String tenantId);
}
