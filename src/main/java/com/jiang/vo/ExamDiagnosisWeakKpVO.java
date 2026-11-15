package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 试卷诊断薄弱知识点条目（{@link ExamDiagnosisVO#getWeakKnowledgePoints()} 的元素）
 *
 * <p>排序口径：{@code wrongCount} 降序 → {@code masteryScore} 升序（无掌握度记录按 0 视为最弱）
 * → {@code knowledgePointId} 升序（稳定兜底），整体截断前 10。knowledgePointId 为 null 的
 * 答错题不计入。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-15
 */
@Data
@Builder
public class ExamDiagnosisWeakKpVO {

    /** 知识点 ID */
    private String knowledgePointId;

    /** 知识点名称（批量映射；知识点行缺失时为 null） */
    private String knowledgePointName;

    /** 该试卷上该知识点的答错题数（已判定为错的题） */
    private Integer wrongCount;

    /** 学生当前掌握度（student_knowledge_state.mastery_score；未评测时为 null） */
    private BigDecimal masteryScore;
}
