package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.AssessmentResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 专项评测结果 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-10-30
 */
@Mapper
public interface AssessmentResultMapper extends BaseMapper<AssessmentResult> {

    /**
     * 状态机更新：pending → graded
     * WHERE status = 'pending' 保证并发安全
     * IMP-2 修复：results 参数为 String 类型（已序列化的 JSON 字符串），
     * 调用方负责用 ObjectMapper 序列化，不经过 JacksonTypeHandler
     *
     * @param assessmentId 评测 ID
     * @param tenantId     租户 ID
     * @param score        得分
     * @param totalScore   总分
     * @param results      详细结果 JSON（含答案）
     * @param assessedAt   批改完成时间
     * @return 影响行数（0 表示并发冲突或状态已变更）
     */
    @Update("UPDATE assessment_result SET " +
            "score = #{score}, total_score = #{totalScore}, " +
            "results = #{results}, status = 'graded', " +
            "assessed_at = #{assessedAt}, update_time = NOW() " +
            "WHERE assessment_id = #{assessmentId} AND tenant_id = #{tenantId} " +
            "AND status = 'pending'")
    int gradeWithCheck(@Param("assessmentId") Long assessmentId,
                       @Param("tenantId") String tenantId,
                       @Param("score") BigDecimal score,
                       @Param("totalScore") BigDecimal totalScore,
                       @Param("results") String results,
                       @Param("assessedAt") LocalDateTime assessedAt);
}
