package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.QuestionBank;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 题库 Mapper 接口
 * <p>
 * 注意：content 列类型为 TEXT（最大 64KB），当前 prompt 较短无风险，
 * 若后续题干内容增长需关注容量上限。
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface QuestionBankMapper extends BaseMapper<QuestionBank> {

    /**
     * 查询指定知识点的平均难度（用于 D_norm 校验）
     * <p>
     * 索引命中：idx_kp_difficulty (knowledge_point_id, tenant_id, difficulty)
     * 覆盖索引完成 AVG 聚合，无需回表。
     *
     * @param kpId     知识点 ID
     * @param tenantId 租户 ID
     * @return 平均难度值
     */
    @Select("SELECT AVG(CASE difficulty " +
            "WHEN 'basic' THEN 0.3 " +
            "WHEN 'standard' THEN 0.6 " +
            "WHEN 'challenge' THEN 0.9 " +
            "ELSE 0.5 END) " +
            "FROM question_bank WHERE knowledge_point_id = #{kpId} AND tenant_id = #{tenantId}")
    BigDecimal selectAvgDifficultyByKp(@Param("kpId") String kpId, @Param("tenantId") String tenantId);

    /**
     * 查询指定知识点的有效题目（用于出题）
     * 索引命中：idx_kp_difficulty (knowledge_point_id, tenant_id, difficulty)
     * 返回完整字段（含 answer，仅 Service 内部批改使用，不对外泄露）
     *
     * @param kpId     知识点 ID
     * @param tenantId 租户 ID
     * @return 题目列表（按 difficulty ASC, question_id ASC 排序）
     */
    @Select("SELECT question_id, knowledge_point_id, content, choices, answer, " +
            "analysis, difficulty, type, tenant_id, create_time, update_time " +
            "FROM question_bank " +
            "WHERE knowledge_point_id = #{kpId} AND tenant_id = #{tenantId} " +
            "ORDER BY difficulty ASC, question_id ASC")
    List<QuestionBank> selectActiveByKp(@Param("kpId") String kpId,
                                         @Param("tenantId") String tenantId);
}
