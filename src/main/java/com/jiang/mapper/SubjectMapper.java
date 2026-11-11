package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.Subject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 科目 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface SubjectMapper extends BaseMapper<Subject> {

    /**
     * 批量查询科目名称（防 N+1）
     */
    @Select("<script>" +
            "SELECT subject_id, name FROM subject " +
            "WHERE subject_id IN " +
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Map<String, Object>> selectNamesByIds(@Param("ids") List<Long> ids);

    /**
     * 按主键 + 租户查询科目（A7：科目校验必须带 tenant_id，禁止裸 selectById 跨租户命中）
     */
    @Select("SELECT subject_id, name, tenant_id FROM subject "
            + "WHERE subject_id = #{subjectId} AND tenant_id = #{tenantId}")
    Subject selectByIdAndTenant(@Param("subjectId") Long subjectId, @Param("tenantId") String tenantId);
}
