package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.vo.*;

import java.time.LocalDate;

/**
 * 管理后端列表查询 Service 接口
 * 修复 IMP-2：不包含 getKnowledgePointList（归 Change 9 负责）
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface AdminListQueryService {

    /**
     * 题目列表查询
     * 修复 IMP-1：questionType 对应 QuestionBank.type
     */
    PageResult<QuestionListVO> getQuestionList(String knowledgePointId, String difficulty,
                                                 String questionType, String keyword,
                                                 String subject, String stage,
                                                 Integer page, Integer size);

    /**
     * 先修关系列表查询
     */
    PageResult<DependencyListVO> getDependencyList(String knowledgePointId, String prerequisiteId,
                                                     String dependencyType,
                                                     String subject, String stage,
                                                     Integer page, Integer size);

    /**
     * 课程标准列表查询
     * 修复 IMP-5：subject 为 String 类型
     */
    PageResult<StandardListVO> getStandardList(String stage, String subject,
                                                 String keyword, Integer page, Integer size);

    /**
     * 导入日志列表查询
     */
    PageResult<ImportLogListVO> getImportLogList(String dataType, LocalDate startDate,
                                                   LocalDate endDate, Integer page, Integer size);

    /**
     * 导入错误记录查询
     */
    PageResult<ImportErrorVO> getImportErrorList(Long importId, Integer page, Integer size);
}
