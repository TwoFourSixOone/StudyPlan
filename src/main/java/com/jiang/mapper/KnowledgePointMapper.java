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
     * 批量查询知识点的 subject 和 standards（用于题目列表关联学科和学段）
     * 返回 Map 结构：knowledge_point_id, subject, standards
     */
    @Select("<script>" +
            "SELECT knowledge_point_id, subject, standards FROM knowledge_point WHERE tenant_id = #{tenantId} AND knowledge_point_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<Map<String, Object>> selectSubjectAndStandardsByIds(@Param("ids") List<String> ids,
                                                              @Param("tenantId") String tenantId);

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
