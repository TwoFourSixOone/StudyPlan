package com.jiang.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiang.model.entity.ExamQuestion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 试卷题目表 Mapper 接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Mapper
public interface ExamQuestionMapper extends BaseMapper<ExamQuestion> {

    /**
     * 批量插入试卷题目（≤100 条/批，禁止逐题单条 insert）
     *
     * <p>显式列名（不使用 {@code select *}）；{@code tenant_id} 由调用方显式写入，
     * {@code create_time}/{@code update_time} 用 {@code NOW()}（自定义 SQL 不走 MyBatis-Plus 自动填充）。</p>
     *
     * @param questions 题目实体（examPaperId/questionIndex/questionContent/attributionStatus/tenantId 必填）
     * @return 插入行数
     */
    @Insert("<script>" +
            "INSERT INTO exam_question " +
            "(exam_paper_id, question_index, question_content, question_image_url, figure_base64, " +
            "knowledge_point_id, " +
            "attribution_status, student_answer, is_correct, score, full_score, question_type, choice_mode, difficulty, " +
            "tenant_id, create_time, update_time) VALUES " +
            "<foreach collection='questions' item='q' separator=','>" +
            "(#{q.examPaperId}, #{q.questionIndex}, #{q.questionContent}, #{q.questionImageUrl}, " +
            "#{q.figureBase64}, " +
            "#{q.knowledgePointId}, #{q.attributionStatus}, #{q.studentAnswer}, #{q.isCorrect}, " +
            "#{q.score}, #{q.fullScore}, #{q.questionType}, #{q.choiceMode}, #{q.difficulty}, " +
            "#{q.tenantId}, NOW(), NOW())" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("questions") List<ExamQuestion> questions);

    /**
     * 统计某份试卷已落库的题目数（TX-A 幂等判重：>0 表示上次已提交，可跳过解析与归因）
     *
     * @param examPaperId 试卷 ID
     * @param tenantId    租户 ID
     * @return 题目数
     */
    @Select("SELECT COUNT(1) FROM exam_question WHERE exam_paper_id = #{examPaperId} " +
            "AND tenant_id = #{tenantId}")
    long countByPaper(@Param("examPaperId") Long examPaperId, @Param("tenantId") String tenantId);

    /**
     * 按题号升序查询试卷题目（判分输入；显式列名，命中 idx_paper_index）
     */
    @Select("SELECT id, exam_paper_id, question_index, question_content, question_image_url, figure_base64, " +
            "knowledge_point_id, attribution_status, student_answer, is_correct, score, full_score, " +
            "question_type, choice_mode, difficulty, tenant_id, create_time, update_time " +
            "FROM exam_question " +
            "WHERE exam_paper_id = #{examPaperId} AND tenant_id = #{tenantId} " +
            "ORDER BY question_index ASC")
    List<ExamQuestion> selectByPaperOrderByIndex(@Param("examPaperId") Long examPaperId,
                                                  @Param("tenantId") String tenantId);

    /**
     * 批量回写判分结果（CASE WHEN，≤100 条/批，禁止逐题 update）
     *
     * <p>按 {@code id} 对齐（不是 question_index，避免题号漂移，design B7）；
     * {@code is_correct} 为 NULL 的题（无法判定）同时把 score/full_score 置 NULL。</p>
     *
     * @param examPaperId 试卷 ID
     * @param tenantId    租户 ID
     * @param questions   待更新题目（只读 id / isCorrect / score / fullScore）
     * @return 受影响行数
     */
    @Update("<script>" +
            "UPDATE exam_question SET " +
            "is_correct = CASE id " +
            "<foreach collection='questions' item='q'>WHEN #{q.id} THEN #{q.isCorrect,jdbcType=INTEGER} </foreach>" +
            "END, " +
            "score = CASE id " +
            "<foreach collection='questions' item='q'>WHEN #{q.id} THEN #{q.score,jdbcType=DECIMAL} </foreach>" +
            "END, " +
            "full_score = CASE id " +
            "<foreach collection='questions' item='q'>WHEN #{q.id} THEN #{q.fullScore,jdbcType=DECIMAL} </foreach>" +
            "END, " +
            "update_time = NOW() " +
            "WHERE exam_paper_id = #{examPaperId} AND tenant_id = #{tenantId} AND id IN " +
            "<foreach collection='questions' item='q' open='(' separator=',' close=')'>#{q.id}</foreach>" +
            "</script>")
    int batchUpdateJudgeResult(@Param("examPaperId") Long examPaperId,
                               @Param("tenantId") String tenantId,
                               @Param("questions") List<ExamQuestion> questions);
}
