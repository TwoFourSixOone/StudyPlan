package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.ExamPaper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 试卷主表 Mapper 接口
 *
 * <p>Change 2026-11-11：新增上传会话（拆分上传）与结算所需的状态机 CAS / JSON 追加能力。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Mapper
public interface ExamPaperMapper extends BaseMapper<ExamPaper> {

    /**
     * 按学生 + 科目 + 考试日期查询试卷（重复上传校验 / 上传会话复用）
     *
     * @return 试卷（可能为 null），含 status / image_urls / id
     */
    @Select("SELECT id, student_id, subject_id, exam_date, total_score, paper_type, image_urls, " +
            "status, grade_status, remark, tenant_id, create_time, update_time " +
            "FROM exam_paper " +
            "WHERE student_id = #{studentId} AND subject_id = #{subjectId} " +
            "AND exam_date = #{examDate} AND tenant_id = #{tenantId}")
    ExamPaper selectByStudentSubjectDate(@Param("studentId") Long studentId,
                                          @Param("subjectId") Long subjectId,
                                          @Param("examDate") LocalDate examDate,
                                          @Param("tenantId") String tenantId);

    /**
     * 追加一张图片 URL（单条原子 JSON 追加，避免读改写竞态）
     *
     * <p>仅 {@code status='uploading'} 且<b>当前张数 &lt; maxImages</b> 的会话可追加（A3：并发 TOCTOU
     * 守卫——两条并发追加时第二条影响行数为 0，调用方据此返回 1189）。</p>
     *
     * @return 0 表示会话状态已变化或张数已达上限
     */
    @Update("UPDATE exam_paper SET image_urls = JSON_ARRAY_APPEND(image_urls, '$', #{imageUrl}), " +
            "update_time = NOW() " +
            "WHERE id = #{paperId} AND tenant_id = #{tenantId} AND status = 'uploading' " +
            "AND JSON_LENGTH(image_urls) < #{maxImages}")
    int appendImageUrl(@Param("paperId") Long paperId,
                       @Param("tenantId") String tenantId,
                       @Param("imageUrl") String imageUrl,
                       @Param("maxImages") int maxImages);

    /**
     * 复用超期 uploading 会话：清空图片列表（JSON 数组重置为 []）
     */
    @Update("UPDATE exam_paper SET image_urls = JSON_ARRAY(), update_time = NOW() " +
            "WHERE id = #{paperId} AND tenant_id = #{tenantId} AND status = 'uploading'")
    int resetImageUrls(@Param("paperId") Long paperId, @Param("tenantId") String tenantId);

    /**
     * 解析失败（且未落任何题目）的试卷：重置为可重新上传的会话（2026-11-12）
     *
     * <p>产品规则 #4：同一科目同一天只允许一份试卷。但<b>整卷解析失败时学生应能重新上传</b>，
     * 否则当天直接卡死（只能等次日）。调用方必须先用 {@code exam_question} 计数确认 0 题——
     * 只有 0 题才没有任何掌握度 / 错题 / 学习记录等副作用，重置是安全的。</p>
     *
     * <p>条件里带 {@code grade_status='ungraded'}：已结算（graded）的记录永不重置。</p>
     *
     * @return 0 表示状态已变化（非 failed 或已结算），调用方按重复上传处理
     */
    @Update("UPDATE exam_paper SET image_urls = JSON_ARRAY(), status = 'uploading', grade_status = 'ungraded', " +
            "remark = NULL, total_score = NULL, update_time = NOW() " +
            "WHERE id = #{paperId} AND tenant_id = #{tenantId} " +
            "AND status = 'failed' AND grade_status = 'ungraded'")
    int resetFailedPaperForReupload(@Param("paperId") Long paperId, @Param("tenantId") String tenantId);

    /**
     * 提交解析：CAS {@code uploading → pending}（与"达量自动触发"只生效一次）
     *
     * @return 0 表示已被提交过或状态非 uploading
     */
    @Update("UPDATE exam_paper SET status = 'pending', update_time = NOW() " +
            "WHERE id = #{paperId} AND tenant_id = #{tenantId} AND status = 'uploading'")
    int casToPending(@Param("paperId") Long paperId, @Param("tenantId") String tenantId);

    /**
     * 重试：CAS {@code failed → pending}
     */
    @Update("UPDATE exam_paper SET status = 'pending', update_time = NOW() " +
            "WHERE id = #{paperId} AND tenant_id = #{tenantId} AND status = 'failed'")
    int casFailedToPending(@Param("paperId") Long paperId, @Param("tenantId") String tenantId);

    /**
     * 结算 CAS：{@code grade_status ungraded → graded}（0 行 = 已被结算过，不重复记错题/掌握度/学习记录）
     *
     * @param totalScore 已判定题数（1 题 1 分制，design §7.5）
     * @return 受影响行数
     */
    @Update("UPDATE exam_paper SET grade_status = 'graded', status = 'completed', " +
            "total_score = #{totalScore}, update_time = NOW() " +
            "WHERE id = #{paperId} AND tenant_id = #{tenantId} AND grade_status = 'ungraded'")
    int casToGraded(@Param("paperId") Long paperId,
                    @Param("tenantId") String tenantId,
                    @Param("totalScore") BigDecimal totalScore);

    // ==================== 历史试卷读接口（Change 2026-11-11 C 组，固定 SQL 条数） ====================

    /**
     * 历史试卷列表：统计总数（列表第 1 条 SQL）
     *
     * <p>命中 {@code idx_student_status (student_id, status, tenant_id)} 前缀，{@code type=ref}。</p>
     */
    @Select("SELECT COUNT(*) FROM exam_paper " +
            "WHERE student_id = #{studentId} AND tenant_id = #{tenantId}")
    long countByStudent(@Param("studentId") Long studentId, @Param("tenantId") String tenantId);

    /**
     * 历史试卷列表：分页 + 题数/正确数/已判定数聚合（列表第 2 条 SQL，派生表包裹规避 MySQL 5.7
     * "LIMIT 不在 IN 子查询"限制）
     *
     * <p>显式列名（禁 {@code select *}）；排序 {@code exam_date DESC, id DESC}；
     * 题数聚合走 {@code idx_paper_index (exam_paper_id, question_index, tenant_id)}。</p>
     */
    @Select("SELECT p.id AS paperId, p.subject_id AS subjectId, s.name AS subjectName, " +
            "p.exam_date AS examDate, p.paper_type AS paperType, p.status AS status, " +
            "p.grade_status AS gradeStatus, p.total_score AS paperTotalScore, p.create_time AS createTime, " +
            "COALESCE(a.questionCount, 0) AS questionCount, " +
            "COALESCE(a.correctCount, 0) AS correctCount, " +
            "COALESCE(a.judgedCount, 0) AS judgedCount " +
            "FROM exam_paper p " +
            "LEFT JOIN subject s ON s.subject_id = p.subject_id AND s.tenant_id = p.tenant_id " +
            "LEFT JOIN (" +
            "  SELECT q.exam_paper_id, COUNT(*) AS questionCount, " +
            "         SUM(CASE WHEN q.is_correct = 1 THEN 1 ELSE 0 END) AS correctCount, " +
            "         SUM(CASE WHEN q.is_correct IS NOT NULL THEN 1 ELSE 0 END) AS judgedCount " +
            "  FROM exam_question q " +
            "  WHERE q.tenant_id = #{tenantId} AND q.exam_paper_id IN (" +
            "    SELECT id FROM (" +
            "      SELECT id FROM exam_paper " +
            "      WHERE student_id = #{studentId} AND tenant_id = #{tenantId} " +
            "      ORDER BY exam_date DESC, id DESC LIMIT #{offset}, #{size}" +
            "    ) page_ids" +
            "  ) " +
            "  GROUP BY q.exam_paper_id" +
            ") a ON a.exam_paper_id = p.id " +
            "WHERE p.student_id = #{studentId} AND p.tenant_id = #{tenantId} " +
            "ORDER BY p.exam_date DESC, p.id DESC " +
            "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectPageWithStats(@Param("studentId") Long studentId,
                                                   @Param("tenantId") String tenantId,
                                                   @Param("offset") int offset,
                                                   @Param("size") int size);

    /**
     * 试卷详情：paper + 科目名（详情第 1 条 SQL，命中 PRIMARY）
     *
     * <p>同时做归属校验：{@code student_id + tenant_id} 不匹配时返回 null → 上层统一 1136，
     * 不区分"不存在"与"非本人"，避免枚举探测。</p>
     */
    @Select("SELECT p.id AS paperId, p.student_id AS studentId, p.subject_id AS subjectId, " +
            "s.name AS subjectName, p.exam_date AS examDate, p.paper_type AS paperType, " +
            "p.image_urls AS imageUrls, p.status AS status, p.grade_status AS gradeStatus, " +
            "p.remark AS remark, p.total_score AS paperTotalScore, p.create_time AS createTime " +
            "FROM exam_paper p " +
            "LEFT JOIN subject s ON s.subject_id = p.subject_id AND s.tenant_id = p.tenant_id " +
            "WHERE p.id = #{paperId} AND p.student_id = #{studentId} AND p.tenant_id = #{tenantId}")
    Map<String, Object> selectDetailWithSubject(@Param("paperId") Long paperId,
                                                 @Param("studentId") Long studentId,
                                                 @Param("tenantId") String tenantId);
}
