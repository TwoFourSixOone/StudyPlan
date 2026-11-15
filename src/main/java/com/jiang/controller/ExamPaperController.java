package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.ExamPaperCreateRequest;
import com.jiang.service.ExamPaperService;
import com.jiang.vo.ExamDiagnosisVO;
import com.jiang.vo.ExamImageUploadVO;
import com.jiang.vo.ExamPaperDetailVO;
import com.jiang.vo.ExamPaperListItemVO;
import com.jiang.vo.ExamSubmitVO;
import com.jiang.vo.ExamTaskProgressVO;
import com.jiang.vo.ExamUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 试卷管理 Controller（Change 2026-11-11：接口收敛到 /exam/papers*，D9）
 *
 * <p>上传三段式（design D1）：建会话 → 逐张传图 → 提交解析；历史回看走 /papers 与 /papers/{id}
 * （以 exam_paper 为准，不复用 exam_analysis_result 的 /results 语义）。multipart 字段名固定 {@code file}（S5）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/student/exam")
@Tag(name = "试卷管理", description = "试卷上传（≤8 图）、解析进度、历史试卷列表与详情、试卷诊断")
@Slf4j
public class ExamPaperController {

    @Autowired
    private ExamPaperService examPaperService;

    /**
     * 创建试卷上传会话（同学生+科目+日期重复上传 → 1131；30 分钟内 uploading 会话复用）
     */
    @PostMapping("/papers")
    @Operation(summary = "创建试卷上传会话")
    public Result<ExamUploadVO> createPaper(@Valid @RequestBody ExamPaperCreateRequest request) {
        log.info("创建试卷上传会话: subjectId={}, examDate={}, paperType={}, imageCount={}",
                request.getSubjectId(), request.getExamDate(), request.getPaperType(),
                request.getImageCount());
        return Result.success(examPaperService.createPaper(
                request.getSubjectId(), request.getExamDate(), request.getPaperType(),
                request.getImageCount()));
    }

    /**
     * 历史试卷列表（固定 2 条 SQL：count + 分页派生表聚合；按考试日期倒序）
     */
    @GetMapping("/papers")
    @Operation(summary = "历史试卷列表")
    public Result<PageResult<ExamPaperListItemVO>> getPapers(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        log.info("历史试卷列表: page={}, size={}", page, size);
        return Result.success(examPaperService.getPapers(page, size));
    }

    /**
     * 试卷详情 + 全部题目（固定 3 条 SQL；他人 paperId → 1136，不泄漏存在性）
     */
    @GetMapping("/papers/{paperId}")
    @Operation(summary = "试卷详情")
    public Result<ExamPaperDetailVO> getPaperDetail(@PathVariable("paperId") Long paperId) {
        log.info("试卷详情: paperId={}", paperId);
        return Result.success(examPaperService.getPaperDetail(paperId));
    }

    /**
     * 试卷诊断聚合（新用户引导方案 A：得分/正确率/薄弱知识点/错题数；固定 4 条 SQL，只读）
     */
    @GetMapping("/papers/{paperId}/diagnosis")
    @Operation(summary = "试卷诊断")
    public Result<ExamDiagnosisVO> getDiagnosis(@PathVariable("paperId") Long paperId) {
        log.info("试卷诊断: paperId={}", paperId);
        return Result.success(examPaperService.getDiagnosis(paperId));
    }

    /**
     * 追加一张试卷图片（multipart 字段名固定 file）
     */
    @PostMapping("/papers/{paperId}/images")
    @Operation(summary = "上传单张试卷图片")
    public Result<ExamImageUploadVO> uploadImage(
            @PathVariable("paperId") Long paperId,
            @RequestParam("file") MultipartFile file) {
        log.info("上传试卷图片: paperId={}, size={}, contentType={}",
                paperId, file == null ? 0 : file.getSize(),
                file == null ? null : file.getContentType());
        return Result.success(examPaperService.uploadImage(paperId, file));
    }

    /**
     * 提交解析（正常触发路径）
     */
    @PostMapping("/papers/{paperId}/submit")
    @Operation(summary = "提交试卷解析")
    public Result<ExamSubmitVO> submitPaper(@PathVariable("paperId") Long paperId) {
        log.info("提交试卷解析: paperId={}", paperId);
        return Result.success(examPaperService.submitPaper(paperId));
    }

    /**
     * 重试解析（仅 failed 且整卷重试次数 < 2）
     */
    @PostMapping("/papers/{paperId}/retry")
    @Operation(summary = "重试试卷解析")
    public Result<ExamSubmitVO> retryPaper(@PathVariable("paperId") Long paperId) {
        log.info("重试试卷解析: paperId={}", paperId);
        return Result.success(examPaperService.retryPaper(paperId));
    }

    /**
     * 查询解析任务进度（归属校验：task → paper 的 student_id + tenant_id）
     */
    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询解析进度")
    public Result<ExamTaskProgressVO> getTaskProgress(@PathVariable("taskId") Long taskId) {
        log.info("查询解析进度: taskId={}", taskId);
        return Result.success(examPaperService.getTaskProgress(taskId));
    }
}
