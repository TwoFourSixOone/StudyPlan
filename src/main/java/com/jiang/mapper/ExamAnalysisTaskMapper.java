package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.ExamAnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 试卷解析任务表 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Mapper
public interface ExamAnalysisTaskMapper extends BaseMapper<ExamAnalysisTask> {

    /**
     * CAS 抢占任务：仅 {@code pending → processing} 成功者才消费（幂等，design §9.2）
     *
     * <p>用显式 UPDATE 而非"先查后改"，避免并发消费/重复投递把同一任务处理两次。</p>
     *
     * @param taskId    任务 ID
     * @param startedAt 开始处理时间
     * @return 受影响行数（0 表示已被处理或状态非 pending）
     */
    @Update("UPDATE exam_analysis_task SET status = 'processing', started_at = #{startedAt}, " +
            "update_time = NOW() WHERE id = #{taskId} AND status = 'pending'")
    int casToProcessing(@Param("taskId") Long taskId, @Param("startedAt") LocalDateTime startedAt);

    /**
     * 查询某份试卷最新的一条解析任务（重试入口用）
     */
    @Select("SELECT id, exam_paper_id, task_type, status, ocr_retry_count, attribution_retry_count, " +
            "error_message, started_at, completed_at, tenant_id, create_time, update_time " +
            "FROM exam_analysis_task " +
            "WHERE exam_paper_id = #{examPaperId} AND tenant_id = #{tenantId} " +
            "ORDER BY id DESC LIMIT 1")
    ExamAnalysisTask selectLatestByPaper(@Param("examPaperId") Long examPaperId,
                                          @Param("tenantId") String tenantId);

    /**
     * 整卷重试：{@code failed → pending} 并累加 {@code ocr_retry_count}（只记整卷重试 0..2）
     *
     * <p>条件 {@code ocr_retry_count < 2} 与状态条件在同一条 SQL 中，避免并发重试叠加；
     * 返回 0 表示不可重试（状态非 failed 或重试次数已用尽）→ 1190。</p>
     *
     * @param taskId   任务 ID
     * @param tenantId 租户 ID
     * @return 受影响行数
     */
    @Update("UPDATE exam_analysis_task SET status = 'pending', ocr_retry_count = ocr_retry_count + 1, " +
            "started_at = NULL, completed_at = NULL, error_message = NULL, update_time = NOW() " +
            "WHERE id = #{taskId} AND tenant_id = #{tenantId} AND status = 'failed' " +
            "AND ocr_retry_count < 2")
    int casFailedToPendingForRetry(@Param("taskId") Long taskId, @Param("tenantId") String tenantId);

    /**
     * 结算收尾：{@code processing → completed}
     */
    @Update("UPDATE exam_analysis_task SET status = 'completed', completed_at = #{completedAt}, " +
            "update_time = NOW() WHERE id = #{taskId} AND status = 'processing'")
    int casToCompleted(@Param("taskId") Long taskId, @Param("completedAt") LocalDateTime completedAt);
}
