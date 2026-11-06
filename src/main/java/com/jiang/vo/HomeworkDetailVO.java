package com.jiang.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 作业详情 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class HomeworkDetailVO {

    /** 总题数 */
    private Integer totalCount;

    /** 正确数 */
    private Integer correctCount;

    /** 正确率 */
    private BigDecimal accuracy;

    /** 逐题详情 */
    private List<HomeworkQuestionDetailVO> questions;
}
