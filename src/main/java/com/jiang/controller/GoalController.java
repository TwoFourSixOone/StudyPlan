package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.CreateGoalRequest;
import com.jiang.dto.UpdateGoalRequest;
import com.jiang.service.GoalService;
import com.jiang.vo.GoalVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端学习目标 Controller
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@RestController
@RequestMapping("/api/student/goal")
@Tag(name = "学生学习目标", description = "学生端学习目标管理接口")
public class GoalController {

    @Autowired
    private GoalService goalService;

    @PostMapping
    @Operation(summary = "创建学习目标")
    public Result<GoalVO> createGoal(@RequestBody @Valid CreateGoalRequest request) {
        return Result.success(goalService.createGoal(request));
    }

    @GetMapping("/active")
    @Operation(summary = "查看当前活跃目标")
    public Result<GoalVO> getActiveGoal(
            @RequestParam(required = false) Long subjectId) {
        return Result.success(goalService.getActiveGoal(subjectId));
    }

    @GetMapping
    @Operation(summary = "查看目标列表（含历史）")
    public Result<PageResult<GoalVO>> getGoalList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(goalService.getGoalList(page, size));
    }

    @GetMapping("/{goalId}")
    @Operation(summary = "查看目标详情")
    public Result<GoalVO> getGoalDetail(@PathVariable Long goalId) {
        return Result.success(goalService.getGoalDetail(goalId));
    }

    @PutMapping("/{goalId}")
    @Operation(summary = "修改目标")
    public Result<GoalVO> updateGoal(@PathVariable Long goalId,
                                      @RequestBody @Valid UpdateGoalRequest request) {
        return Result.success(goalService.updateGoal(goalId, request));
    }

    @PostMapping("/{goalId}/complete")
    @Operation(summary = "结束目标")
    public Result<Void> completeGoal(@PathVariable Long goalId) {
        goalService.completeGoal(goalId);
        return Result.success();
    }
}
