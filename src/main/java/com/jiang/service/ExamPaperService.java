package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.dto.ExamGradeRequest;
import com.jiang.vo.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * 试卷服务接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface ExamPaperService {

    /**
     * 上传试卷
     * 修复 IMP-6：文件上传在事务外，仅 DB 写入在事务内
     *
     * @param subjectId 科目 ID
     * @param examDate  考试日期
     * @param paperType 试卷类型
     * @param files     试卷图片文件列表
     * @return 上传结果
     */
    ExamUploadVO uploadExamPaper(Long subjectId, LocalDate examDate, String paperType, List<MultipartFile> files);

    /**
     * 处理试卷解析任务（由 Worker 调用）
     * 修复 BLOCK-5：同步处理，不使用 @Async
     *
     * @param taskId 任务 ID
     */
    void processExamAnalysis(Long taskId);

    /**
     * 提交考试成绩
     * 修复 BLOCK-6：学生手动录入成绩后触发掌握度更新和错题入队
     * 修复 IMP-25：错题入队先于掌握度更新
     *
     * @param resultId 试卷 ID
     * @param request  成绩明细
     */
    void submitExamGrade(Long resultId, ExamGradeRequest request);

    /**
     * 查询解析任务进度
     *
     * @param taskId 任务 ID
     * @return 进度信息
     */
    ExamTaskProgressVO getTaskProgress(Long taskId);

    /**
     * 获取分析结果详情
     *
     * @param resultId 试卷 ID
     * @return 详情
     */
    ExamResultDetailVO getResultDetail(Long resultId);

    /**
     * 获取分析结果历史列表
     *
     * @param page 页码（从 1 开始）
     * @param size 每页大小
     * @return 分页结果
     */
    PageResult<ExamResultListVO> getResultHistory(Integer page, Integer size);
}
