package com.jiang.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 考试详情 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class ExamDetailVO {

    /** 考试名称 */
    private String examName;

    /** 用户得分 */
    private BigDecimal userScore;

    /** 总分 */
    private BigDecimal totalScore;

    /** 得分率 */
    private BigDecimal scoreRate;

    /** 逐题详情 */
    private List<ExamQuestionDetailVO> questions;
}
