package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.service.GoalService;
import com.jiang.vo.GoalVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端学习目标 Controller（只读视图）
 *
 * <p>与学生端 GoalController 的差异：
 * 管理端不做 studentId 所有权过滤，仅做 tenantId 隔离。
 * tenantId 从 StudentContext.getTenantId() 获取（N1：Demo 阶段复用学生上下文）。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@RestController
@RequestMapping("/api/admin/goal")
@Tag(name = "管理端 - 学习目标", description = "管理端学习目标查询接口")
public class AdminGoalController {

    @Autowired
    private GoalService goalService;

    @GetMapping
    @Operation(summary = "查询目标列表（筛选 + 分页）")
    public Result<PageResult<GoalVO>> getGoalList(
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(goalService.adminGetGoalList(studentId, subjectId, status, page, size));
    }

    @GetMapping("/{goalId}")
    @Operation(summary = "查看目标详情")
    public Result<GoalVO> getGoalDetail(@PathVariable Long goalId) {
        return Result.success(goalService.adminGetGoalDetail(goalId));
    }
}
