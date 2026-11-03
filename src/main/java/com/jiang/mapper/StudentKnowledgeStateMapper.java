package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.StudentKnowledgeState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 学生知识点掌握度状态 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface StudentKnowledgeStateMapper extends BaseMapper<StudentKnowledgeState> {

    /**
     * 查询学生 active 知识点的掌握度状态（JOIN knowledge_point）
     * <p>
     * 返回的 StudentKnowledgeState 中包含 kpName、kpSubject、kpStatus 三个瞬态字段，
     * 用于看板展示时减少 N+1 查询。
     *
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @param subject   科目筛选（可为 null）
     * @return 包含知识点信息的状态列表
     */
    @Select("SELECT s.state_id, s.student_id, s.knowledge_point_id, s.mastery_score, " +
            "s.confidence, s.source, s.last_assessed_at, s.version, " +
            "s.tenant_id, s.create_time, s.update_time, " +
            "k.name AS kp_name, k.subject AS kp_subject, k.status AS kp_status " +
            "FROM student_knowledge_state s " +
            "JOIN knowledge_point k ON s.knowledge_point_id = k.knowledge_point_id " +
            "AND s.tenant_id = k.tenant_id " +
            "WHERE s.student_id = #{studentId} AND s.tenant_id = #{tenantId} " +
            "AND k.status = 'active' " +
            "AND (#{subject} IS NULL OR k.subject = #{subject})")
    List<StudentKnowledgeState> selectActiveStatesWithKpInfo(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("subject") String subject);
}
