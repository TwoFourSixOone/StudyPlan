package com.jiang.controller;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.InMemoryMultipartFile;
import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.ExamImageBase64Request;
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

import java.util.Base64;

import java.util.Base64;

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
     *
     * <p>2026-11-17：新增可选 {@code pageNo}（1..8，图片在用户选择列表中的位置），用于还原卷面页序；
     * 旧客户端不传时服务端按「当前张数+1」补齐，行为等价于到达顺序。</p>
     */
    @PostMapping("/papers/{paperId}/images")
    @Operation(summary = "上传单张试卷图片")
    public Result<ExamImageUploadVO> uploadImage(
            @PathVariable("paperId") Long paperId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "pageNo", required = false) Integer pageNo) {
        log.info("上传试卷图片: paperId={}, size={}, contentType={}, pageNo={}",
                paperId, file == null ? 0 : file.getSize(),
                file == null ? null : file.getContentType(), pageNo);
        return Result.success(examPaperService.uploadImage(paperId, file, pageNo));
    }

    /**
     * 追加一张试卷图片（base64，云托管 callContainer 通道）
     *
     * <p>Change: 2026-11-23_upload_via_call_container —— 云托管默认域名不能用于正式环境的
     * 「uploadFile 合法域名」，因此小程序在云端模式改走 {@code wx.cloud.callContainer}（内网通道，无需白名单），
     * 图片以 base64 放进 JSON body。解码后用 {@link com.jiang.common.util.InMemoryMultipartFile} 适配，
     * **完全复用** multipart 版的校验链（归属/张数/累计大小/类型魔数/分辨率/落盘/页序）。</p>
     */
    @PostMapping("/papers/{paperId}/images/base64")
    @Operation(summary = "上传单张试卷图片（base64，云托管通道）")
    public Result<ExamImageUploadVO> uploadImageBase64(
            @PathVariable("paperId") Long paperId,
            @RequestBody @Valid ExamImageBase64Request request) {
        byte[] bytes = decodeBase64Image(request.getDataBase64());
        String contentType = (request.getContentType() == null || request.getContentType().isBlank())
                ? "image/jpeg" : request.getContentType();
        log.info("上传试卷图片(base64): paperId={}, size={}, contentType={}, pageNo={}",
                paperId, bytes.length, contentType, request.getPageNo());
        MultipartFile file = new InMemoryMultipartFile("file", "paper-image", contentType, bytes);
        return Result.success(examPaperService.uploadImage(paperId, file, request.getPageNo()));
    }

    /**
     * base64 解码（容忍换行/空白），并做前置长度保护
     */
    private byte[] decodeBase64Image(String dataBase64) {
        if (dataBase64 == null || dataBase64.isBlank()) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_EMPTY);
        }
        // base64 长度粗判：解码后字节数 ≈ len * 3 / 4；先拦明显超限的请求，避免无谓的大字符串解码
        long estimatedBytes = (long) dataBase64.length() * 3 / 4;
        if (estimatedBytes > 10L * 1024 * 1024) {
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_TOO_LARGE);
        }
        try {
            return Base64.getMimeDecoder().decode(dataBase64);
        } catch (IllegalArgumentException e) {
            log.warn("base64 图片解码失败: paperId 解析前, error={}", e.getMessage());
            throw new BusinessException(ErrorCodes.EXAM_IMAGE_EMPTY);
        }
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
