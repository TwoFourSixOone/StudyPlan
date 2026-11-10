package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.MasteryChangeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 掌握度变更日志 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface MasteryChangeLogMapper extends BaseMapper<MasteryChangeLog> {

    /**
     * 统计变更历史总数（与 selectHistoryByStudentAndKp 配套使用）
     *
     * @param studentId        学生 ID
     * @param tenantId         租户 ID
     * @param knowledgePointId 知识点 ID
     * @return 记录总数
     */
    @Select("SELECT COUNT(*) FROM mastery_change_log " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId}")
    long countHistoryByStudentAndKp(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("knowledgePointId") String knowledgePointId);

    /**
     * 分页查询变更历史（按时间倒序）
     *
     * @param studentId        学生 ID
     * @param tenantId         租户 ID
     * @param knowledgePointId 知识点 ID
     * @param offset           偏移量
     * @param size             每页大小
     * @return 变更日志列表
     */
    @Select("SELECT log_id, student_id, knowledge_point_id, change_type, source_id, " +
            "delta, mastery_before, mastery_after, tenant_id, create_time, update_time " +
            "FROM mastery_change_log " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND knowledge_point_id = #{knowledgePointId} " +
            "ORDER BY create_time DESC LIMIT #{offset}, #{size}")
    List<MasteryChangeLog> selectHistoryByStudentAndKp(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("knowledgePointId") String knowledgePointId,
            @Param("offset") int offset,
            @Param("size") int size);

    /**
     * 批量查询指定知识点列表的最新一条变更日志
     * <p>
     * 用于看板 lastChangeDirection 组装，每个知识点最多返回一条记录（log_id 最大者）。
     * <p>
     * 索引命中：idx_student_kp_log (student_id, tenant_id, knowledge_point_id, log_id)
     * 子查询通过覆盖索引完成 MAX(log_id) 聚合，无需回表。
     *
     * @param studentId 学生 ID
     * @param tenantId  租户 ID
     * @param kpIds     知识点 ID 列表
     * @return 最新变更日志列表
     */
    @Select("<script>" +
            "SELECT mcl.log_id, mcl.student_id, mcl.knowledge_point_id, mcl.change_type, " +
            "mcl.source_id, mcl.delta, mcl.mastery_before, mcl.mastery_after, " +
            "mcl.tenant_id, mcl.create_time, mcl.update_time " +
            "FROM mastery_change_log mcl " +
            "INNER JOIN (" +
            "  SELECT knowledge_point_id, MAX(log_id) AS max_log_id " +
            "  FROM mastery_change_log " +
            "  WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "  AND knowledge_point_id IN " +
            "  <foreach item='kpId' collection='kpIds' open='(' separator=',' close=')'>#{kpId}</foreach> " +
            "  GROUP BY knowledge_point_id" +
            ") latest ON mcl.log_id = latest.max_log_id" +
            "</script>")
    List<MasteryChangeLog> selectLatestByKpIds(
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("kpIds") List<String> kpIds);

    /**
     * 查询学生指定科目考试导致的最大掌握度下降值（SIG-2 信号检测）
     * <p>
     * 修复 BadSqlGrammar：原 SQL 引用 exam_paper 表不存在的 exam_paper_id/goal_id 列。
     * 实际表结构：exam_paper 主键为 id，无 goal_id，与目标无直接关联；
     * 改为按目标所属学生 + 科目维度关联（source_id = exam_paper.id），
     * change_type = 'exam' 限定考试来源，保持 tenant_id 隔离。
     *
     * @param studentId 学生 ID
     * @param subjectId 科目 ID
     * @param tenantId  租户 ID
     * @return 最大下降值（负数表示下降，如 -0.3；无记录时返回 null）
     */
    @Select("SELECT MIN(delta) FROM mastery_change_log " +
            "WHERE change_type = 'exam' AND delta < 0 " +
            "AND student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "AND source_id IN (" +
            "  SELECT id FROM exam_paper WHERE student_id = #{studentId} " +
            "  AND subject_id = #{subjectId} AND tenant_id = #{tenantId}" +
            ")")
    Double selectMaxMasteryDropByStudentAndSubject(@Param("studentId") Long studentId,
                                                   @Param("subjectId") Long subjectId,
                                                   @Param("tenantId") String tenantId);

    /**
     * 按知识点查询掌握度变更历史（学习记录-掌握度变更）
     */
    @Select("SELECT * FROM mastery_change_log " +
            "WHERE knowledge_point_id = #{knowledgePointId} " +
            "AND student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<MasteryChangeLog> selectByKnowledgePointIdAndStudentId(
            @Param("knowledgePointId") String knowledgePointId,
            @Param("studentId") Long studentId,
            @Param("tenantId") String tenantId,
            @Param("limit") int limit);

    /**
     * 按来源查询掌握度变更（IMP-17 修复）
     */
    @Select("SELECT * FROM mastery_change_log " +
            "WHERE source_id = #{sourceId} AND change_type = #{changeType} " +
            "AND tenant_id = #{tenantId} " +
            "ORDER BY create_time DESC")
    List<MasteryChangeLog> selectBySourceIdAndType(@Param("sourceId") Long sourceId,
                                                    @Param("changeType") String changeType,
                                                    @Param("tenantId") String tenantId);
}
