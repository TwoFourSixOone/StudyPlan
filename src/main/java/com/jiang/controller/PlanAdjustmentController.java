package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.AdjustRequestDTO;
import com.jiang.service.PlanAdjustmentService;
import com.jiang.vo.AdjustTriggerVO;
import com.jiang.vo.AdjustmentHistoryVO;
import com.jiang.vo.PlanDiffDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 计划调整 Controller
 * 修复 IMP-23：/adjust 使用 POST + @RequestBody
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/student/plan")
@Tag(name = "计划调整", description = "计划调整相关接口")
public class PlanAdjustmentController {

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    /**
     * 修复 IMP-23：触发调整是写操作，使用 POST + @RequestBody
     */
    @PostMapping("/adjust")
    @Operation(summary = "触发调整")
    public Result<AdjustTriggerVO> triggerAdjustment(@RequestBody @Valid AdjustRequestDTO request) {
        return Result.success(planAdjustmentService.triggerAdjustment(request.getGoalId()));
    }

    @GetMapping("/diff/{diffId}")
    @Operation(summary = "获取 diff 详情")
    public Result<PlanDiffDetailVO> getDiffDetail(@PathVariable Long diffId) {
        return Result.success(planAdjustmentService.getDiffDetail(diffId));
    }

    @PostMapping("/diff/{diffId}/confirm")
    @Operation(summary = "确认 diff")
    public Result<Void> confirmDiff(@PathVariable Long diffId) {
        planAdjustmentService.confirmDiff(diffId);
        return Result.success();
    }

    @PostMapping("/diff/{diffId}/reject")
    @Operation(summary = "拒绝 diff")
    public Result<Void> rejectDiff(@PathVariable Long diffId) {
        planAdjustmentService.rejectDiff(diffId);
        return Result.success();
    }

    @GetMapping("/adjustments")
    @Operation(summary = "获取调整历史")
    public Result<PageResult<AdjustmentHistoryVO>> getAdjustmentHistory(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return Result.success(planAdjustmentService.getAdjustmentHistory(page, size));
    }
}
