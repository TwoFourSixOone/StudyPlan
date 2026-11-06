package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.StandardListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 课程标准列表 Controller
 * 修复 BLOCK-1/N1：Demo 阶段复用 StudentContext.getTenantId()
 * 修复 IMP-5：subject 参数为 String 类型
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/admin/standards")
@Tag(name = "课程标准管理（管理端）", description = "课程标准列表查询")
public class AdminStandardController {

    @Autowired
    private AdminListQueryService adminListQueryService;

    @GetMapping
    @Operation(summary = "课程标准列表查询")
    public Result<PageResult<StandardListVO>> getStandardList(
            @RequestParam(value = "stage", required = false) String stage,
            // 修复 IMP-5：subject 为 String 类型（非 Long）
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        size = Math.min(size, 100);
        return Result.success(adminListQueryService.getStandardList(
                stage, subject, keyword, page, size));
    }
}
