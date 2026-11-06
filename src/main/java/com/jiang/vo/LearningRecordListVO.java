package com.jiang.vo;

import com.jiang.model.enums.RecordType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习记录列表 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class LearningRecordListVO {

    /** 记录 ID */
    private Long recordId;

    /** 记录类型 */
    private RecordType recordType;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 科目 ID */
    private Long subjectId;

    /** 科目名称（后处理填充） */
    private String subjectName;

    /** 标题 */
    private String title;

    /** 分数（评测/考试有值） */
    private BigDecimal score;
}
