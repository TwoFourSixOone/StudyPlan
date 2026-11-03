package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.CreateAssessmentRequest;
import com.jiang.dto.SubmitAssessmentRequest;
import com.jiang.security.StudentContext;
import com.jiang.service.AssessmentService;
import com.jiang.vo.AssessmentDetailVO;
import com.jiang.vo.AssessmentHistoryVO;
import com.jiang.vo.AssessmentResultVO;
import com.jiang.vo.AssessmentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端专项评测 Controller
 *
 * @author StudyMate Team
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/api/student/assessment")
@Tag(name = "专项评测", description = "学生端专项评测接口")
public class AssessmentController {

    @Autowired
    private AssessmentService assessmentService;

    @PostMapping
    @Operation(summary = "创建评测（系统自动出题）")
    public Result<AssessmentVO> createAssessment(@RequestBody @Valid CreateAssessmentRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        return Result.success(assessmentService.createAssessment(studentId, tenantId, request.getSubjectId()));
    }

    @GetMapping("/{assessmentId}")
    @Operation(summary = "查看评测详情（含题目内容，不含答案）")
    public Result<AssessmentDetailVO> getAssessmentDetail(@PathVariable Long assessmentId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        return Result.success(assessmentService.getAssessmentDetail(studentId, tenantId, assessmentId));
    }

    @PostMapping("/{assessmentId}/submit")
    @Operation(summary = "提交评测答案（一次性提交，触发批改）")
    public Result<AssessmentResultVO> submitAssessment(
            @PathVariable Long assessmentId,
            @RequestBody @Valid SubmitAssessmentRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        return Result.success(assessmentService.submitAndGrade(
                studentId, tenantId, assessmentId, request.getAnswers()));
    }

    @GetMapping
    @Operation(summary = "评测历史列表（分页）")
    public Result<PageResult<AssessmentHistoryVO>> getAssessmentHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        return Result.success(assessmentService.getAssessmentHistory(studentId, tenantId, page, size));
    }
}
