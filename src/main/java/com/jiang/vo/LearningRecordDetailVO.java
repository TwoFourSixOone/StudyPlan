package com.jiang.vo;

import com.jiang.model.enums.RecordType;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习记录详情 VO
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Data
public class LearningRecordDetailVO {

    /** 记录 ID */
    private Long id;

    /** 记录类型 */
    private RecordType recordType;

    /** 记录日期 */
    private LocalDate recordDate;

    /** 科目名称 */
    private String subjectName;

    /** 标题 */
    private String title;

    /** 详情（根据 recordType 不同为不同子类） */
    private Object detail;

    /** 关联的掌握度变更 */
    private List<MasteryChangeVO> masteryChanges;
}
