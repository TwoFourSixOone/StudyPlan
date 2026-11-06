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
     * 统计指定目标下从未学习过的知识点数量（SIG-4 信号检测）
     * 修复 BLOCK-14：kUncovered = masteryScore IS NULL（从未学习）
     * 修复 H-4：增加 tenant_id 隔离
     *
     * @param goalId   目标 ID
     * @param tenantId 租户 ID
     * @return 未覆盖知识点数量
     */
    @Select("SELECT COUNT(*) FROM student_knowledge_state s " +
            "JOIN learning_goal_knowledge g ON s.knowledge_point_id = g.knowledge_point_id " +
            "WHERE g.goal_id = #{goalId} " +
            "AND s.tenant_id = #{tenantId} " +
            "AND (s.mastery_score IS NULL OR s.mastery_score = 0)")
    long countUncoveredByGoalId(@Param("goalId") Long goalId,
                                 @Param("tenantId") String tenantId);

    /**
     * 按学生和科目查询掌握度状态（用于停用知识点展示规则）
     */
    @Select("SELECT * FROM student_knowledge_state " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId}")
    List<StudentKnowledgeState> selectByStudentAndSubject(@Param("studentId") Long studentId,
                                                           @Param("subject") String subject,
                                                           @Param("tenantId") String tenantId);
}
