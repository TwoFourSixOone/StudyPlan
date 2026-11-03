package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.security.StudentContext;
import com.jiang.service.ErrorQuestionService;
import com.jiang.vo.ErrorQuestionStatisticsVO;
import com.jiang.vo.ErrorQuestionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端错题管理 Controller
 *
 * <p>提供错题列表分页查询和错题统计接口。</p>
 * <p>所有接口从 StudentContext 提取 studentId/tenantId（BLOCK-2 复用）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@RestController
@RequestMapping("/api/error-questions")
@Tag(name = "错题管理", description = "学生端错题查询与统计接口")
public class ErrorQuestionController {

    @Autowired
    private ErrorQuestionService errorQuestionService;

    /**
     * 错题列表分页查询
     * GET /api/error-questions?knowledgePointId=xxx&status=active&page=1&size=10
     *
     * IMP-F: page < 1 置 1，size > 100 截断至 100
     */
    @GetMapping
    @Operation(summary = "查询错题列表（分页）")
    public Result<PageResult<ErrorQuestionVO>> getErrorQuestions(
            @RequestParam(required = false) String knowledgePointId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        // IMP-F: 参数校验
        if (page < 1) {
            page = 1;
        }
        if (size > 100) {
            size = 100;
        }
        if (size < 1) {
            size = 1;
        }
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        PageResult<ErrorQuestionVO> result = errorQuestionService.getErrorQuestions(
                studentId, tenantId, knowledgePointId, status, page, size);
        return Result.success(result);
    }

    /**
     * 错题统计
     * GET /api/error-questions/statistics
     */
    @GetMapping("/statistics")
    @Operation(summary = "查询错题统计")
    public Result<ErrorQuestionStatisticsVO> getStatistics() {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        ErrorQuestionStatisticsVO stats = errorQuestionService.getStatistics(studentId, tenantId);
        return Result.success(stats);
    }
}
