package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.GeneratedQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Agent 生成新题 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface GeneratedQuestionMapper extends BaseMapper<GeneratedQuestion> {

    /**
     * 查询指定知识点下已生成且通过校验的新题内容（用于相似度比较）
     * IMP-15 修正：仅取最近 100 条，按生成时间倒序，更早的题由唯一索引防重
     * Change: 2026-11-03_similar_question_generation
     */
    @Select("SELECT content FROM generated_question " +
            "WHERE knowledge_point_id = #{kpId} AND tenant_id = #{tenantId} " +
            "AND similarity_checked = 1 " +
            "ORDER BY create_time DESC " +
            "LIMIT 100")
    List<String> selectCheckedContentsByKp(@Param("kpId") String kpId,
                                           @Param("tenantId") String tenantId);
}
