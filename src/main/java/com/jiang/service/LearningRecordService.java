package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.model.enums.RecordType;
import com.jiang.vo.LearningRecordDetailVO;
import com.jiang.vo.LearningRecordListVO;
import com.jiang.vo.MasteryChangeVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习记录服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface LearningRecordService {

    /**
     * 查询学习记录列表（BLOCK-C：SQL UNION ALL 统一分页）
     */
    PageResult<LearningRecordListVO> getRecordList(LocalDate startDate, LocalDate endDate,
                                                     RecordType recordType, Integer page, Integer size);

    /**
     * 查询学习记录详情（BLOCK-11：按 RecordType 分派）
     */
    LearningRecordDetailVO getRecordDetail(RecordType type, Long id);

    /**
     * 查询掌握度变更历史
     */
    List<MasteryChangeVO> getMasteryChanges(String knowledgePointId, Integer limit);
}
