package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.DependencyListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 先修关系列表 Controller
 * 修复 BLOCK-1/N1：Demo 阶段复用 StudentContext.getTenantId()
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/admin/dependencies")
@Tag(name = "先修关系管理（管理端）", description = "先修关系列表查询")
public class AdminDependencyController {

    @Autowired
    private AdminListQueryService adminListQueryService;

    @GetMapping
    @Operation(summary = "先修关系列表查询")
    public Result<PageResult<DependencyListVO>> getDependencyList(
            @RequestParam(value = "knowledgePointId", required = false) String knowledgePointId,
            @RequestParam(value = "prerequisiteId", required = false) String prerequisiteId,
            @RequestParam(value = "dependencyType", required = false) String dependencyType,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        size = Math.min(size, 100);
        return Result.success(adminListQueryService.getDependencyList(
                knowledgePointId, prerequisiteId, dependencyType, subject, stage, page, size));
    }
}
