package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.service.KnowledgePointGovernanceService;
import com.jiang.vo.KnowledgePointDetailVO;
import com.jiang.vo.KnowledgePointListVO;
import com.jiang.vo.KnowledgePointOverdueVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识点管理 Controller（管理端）
 * 修复 BLOCK-1/N1：Demo 阶段复用 StudentContext.getTenantId()
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/admin/knowledge-points")
@Tag(name = "知识点管理（管理端）", description = "知识点列表、启停用、校对标记")
public class AdminKnowledgePointController {

    @Autowired
    private KnowledgePointGovernanceService knowledgePointGovernanceService;

    @GetMapping
    @Operation(summary = "知识点列表查询")
    public Result<PageResult<KnowledgePointListVO>> getKnowledgePointList(
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return Result.success(knowledgePointGovernanceService.getKnowledgePointList(
                subject, status, keyword, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "知识点详情查看")
    public Result<KnowledgePointDetailVO> getKnowledgePointDetail(@PathVariable String id) {
        return Result.success(knowledgePointGovernanceService.getKnowledgePointDetail(id));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "知识点启停用")
    public Result<Void> updateStatus(
            @PathVariable String id,
            @RequestParam String status) {
        knowledgePointGovernanceService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 修复 BLOCK-9：幂等操作
     */
    @PutMapping("/{id}/review")
    @Operation(summary = "知识点校对标记")
    public Result<Void> markAsReviewed(@PathVariable String id) {
        knowledgePointGovernanceService.markAsReviewed(id);
        return Result.success();
    }

    @GetMapping("/overdue")
    @Operation(summary = "校对超期列表")
    public Result<List<KnowledgePointOverdueVO>> getOverdueKnowledgePoints() {
        return Result.success(knowledgePointGovernanceService.getOverdueKnowledgePoints());
    }

    @PutMapping("/batch-review")
    @Operation(summary = "批量校对标记", description = "传入 ID 列表则校验指定知识点，传空列表则校验所有待校对知识点")
    public Result<Integer> batchMarkAsReviewed(@RequestBody List<String> ids) {
        int count = knowledgePointGovernanceService.batchMarkAsReviewed(ids);
        return Result.success(count);
    }
}
