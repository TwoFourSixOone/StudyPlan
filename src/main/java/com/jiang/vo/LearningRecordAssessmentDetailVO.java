package com.jiang.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 学习记录-评测详情 VO
 * 用于 LearningRecordService.getRecordDetail (ASSESSMENT 类型)
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class LearningRecordAssessmentDetailVO {

    /** 评测得分 */
    private BigDecimal score;

    /** 总题数 */
    private Integer totalCount;

    /** 正确数 */
    private Integer correctCount;

    /** 正确率 */
    private BigDecimal accuracy;

    /** 涉及的知识点列表 */
    private List<String> knowledgePointIds;
}
