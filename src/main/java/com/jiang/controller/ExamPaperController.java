package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.ExamGradeRequest;
import com.jiang.service.ExamPaperService;
import com.jiang.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * 试卷管理 Controller
 * 修复 BLOCK-6：新增成绩录入接口
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/student/exam")
@Tag(name = "试卷管理", description = "试卷上传、成绩录入与解析结果查询")
@Slf4j
public class ExamPaperController {

    @Autowired
    private ExamPaperService examPaperService;

    /**
     * 上传试卷（不含批改）
     * 修复 IMP-6：文件上传在事务外，仅 DB 写入在事务内
     */
    @PostMapping("/upload")
    @Operation(summary = "上传试卷")
    public Result<ExamUploadVO> uploadExamPaper(
            @RequestParam("subjectId") Long subjectId,
            @RequestParam("examDate") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate examDate,
            @RequestParam(value = "paperType", defaultValue = "midterm") String paperType,
            @RequestParam("files") List<MultipartFile> files) {
        log.info("上传试卷: subjectId={}, examDate={}, paperType={}, fileCount={}",
                subjectId, examDate, paperType, files.size());
        return Result.success(examPaperService.uploadExamPaper(subjectId, examDate, paperType, files));
    }

    /**
     * 录入考试成绩
     * 修复 BLOCK-6：学生手动录入每道题的对错/得分，触发掌握度更新和错题入队
     */
    @PostMapping("/result/{resultId}/grade")
    @Operation(summary = "录入考试成绩")
    public Result<Void> submitExamGrade(
            @PathVariable Long resultId,
            @RequestBody @Valid ExamGradeRequest request) {
        log.info("录入考试成绩: resultId={}", resultId);
        examPaperService.submitExamGrade(resultId, request);
        return Result.success();
    }

    /**
     * 查询解析任务进度
     */
    @GetMapping("/task/{taskId}")
    @Operation(summary = "查询解析进度")
    public Result<ExamTaskProgressVO> getTaskProgress(@PathVariable Long taskId) {
        log.info("查询解析进度: taskId={}", taskId);
        return Result.success(examPaperService.getTaskProgress(taskId));
    }

    /**
     * 查看分析结果详情
     */
    @GetMapping("/result/{resultId}")
    @Operation(summary = "查看分析结果详情")
    public Result<ExamResultDetailVO> getResultDetail(@PathVariable Long resultId) {
        log.info("查看分析结果详情: resultId={}", resultId);
        return Result.success(examPaperService.getResultDetail(resultId));
    }

    /**
     * 查看分析结果历史列表
     */
    @GetMapping("/results")
    @Operation(summary = "查看分析结果历史列表")
    public Result<PageResult<ExamResultListVO>> getResultHistory(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        log.info("查看分析结果历史: page={}, size={}", page, size);
        return Result.success(examPaperService.getResultHistory(page, size));
    }
}
