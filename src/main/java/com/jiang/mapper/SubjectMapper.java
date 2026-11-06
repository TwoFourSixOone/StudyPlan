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
}
