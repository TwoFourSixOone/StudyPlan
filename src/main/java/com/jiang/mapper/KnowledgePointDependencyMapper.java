package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.KnowledgePointDependency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 知识点先修关系 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface KnowledgePointDependencyMapper extends BaseMapper<KnowledgePointDependency> {

    /**
     * 查询知识点的先修关系
     */
    @Select("SELECT * FROM knowledge_point_dependency " +
            "WHERE (knowledge_point_id = #{kpId} OR prerequisite_id = #{kpId}) " +
            "AND tenant_id = #{tenantId}")
    List<KnowledgePointDependency> selectByKnowledgePointId(@Param("kpId") String kpId,
                                                             @Param("tenantId") String tenantId);

    /**
     * 批量查询每个知识点的先修关系数量（作为 prerequisite 或 knowledge_point 参与的关系总数）
     * 返回 Map 结构：knowledge_point_id, cnt
     */
    @Select("<script>" +
            "SELECT knowledge_point_id, COUNT(*) AS cnt FROM knowledge_point_dependency " +
            "WHERE tenant_id = #{tenantId} AND knowledge_point_id IN " +
            "<foreach collection='kpIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "GROUP BY knowledge_point_id" +
            "</script>")
    List<Map<String, Object>> countAsKnowledgePoint(@Param("kpIds") List<String> kpIds,
                                                     @Param("tenantId") String tenantId);

    /**
     * 批量查询每个知识点作为 prerequisite 被依赖的数量
     * 返回 Map 结构：prerequisite_id, cnt
     */
    @Select("<script>" +
            "SELECT prerequisite_id, COUNT(*) AS cnt FROM knowledge_point_dependency " +
            "WHERE tenant_id = #{tenantId} AND prerequisite_id IN " +
            "<foreach collection='kpIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "GROUP BY prerequisite_id" +
            "</script>")
    List<Map<String, Object>> countAsPrerequisite(@Param("kpIds") List<String> kpIds,
                                                   @Param("tenantId") String tenantId);
}
