package com.jiang.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批改结果视图对象
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradeResultVO {

    /** 总题数 */
    private Integer totalQuestions;

    /** 答对题数 */
    private Integer correctCount;

    /** 答错题数 */
    private Integer incorrectCount;

    /** 正确率 */
    private BigDecimal accuracy;

    /** 受影响的知识点 ID 列表（去重后） */
    private List<String> affectedKnowledgePoints;
}
