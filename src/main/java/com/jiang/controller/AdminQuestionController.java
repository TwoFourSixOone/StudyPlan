package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.QuestionListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 题目列表 Controller
 * 修复 BLOCK-1/N1：Demo 阶段复用 StudentContext.getTenantId()
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/admin/questions")
@Tag(name = "题目管理（管理端）", description = "题目列表查询")
public class AdminQuestionController {

    @Autowired
    private AdminListQueryService adminListQueryService;

    @GetMapping
    @Operation(summary = "题目列表查询")
    public Result<PageResult<QuestionListVO>> getQuestionList(
            @RequestParam(value = "knowledgePointId", required = false) String knowledgePointId,
            @RequestParam(value = "difficulty", required = false) String difficulty,
            @RequestParam(value = "questionType", required = false) String questionType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        // 修复 IMP-4：size 最大限制 100（Service 层已处理，Controller 也做防御）
        size = Math.min(size, 100);
        return Result.success(adminListQueryService.getQuestionList(
                knowledgePointId, difficulty, questionType, keyword, subject, stage, page, size));
    }
}
