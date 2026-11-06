package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.service.MasteryService;
import com.jiang.vo.MasteryChangeLogVO;
import com.jiang.vo.MasteryDashboardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端掌握度 Controller
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@RestController
@RequestMapping("/api/student/mastery")
@Tag(name = "学生端 - 掌握度", description = "学生端掌握度看板与变更历史接口")
public class MasteryController {

    @Autowired
    private MasteryService masteryService;

    @GetMapping("/dashboard")
    @Operation(summary = "获取掌握度看板")
    public Result<List<MasteryDashboardVO>> getDashboard(
            @RequestParam(required = false) String subject,
            @RequestParam(value = "showDisabled", required = false) Boolean showDisabled) {
        // 修复 IMP-14：Boolean null 安全处理
        boolean show = Boolean.TRUE.equals(showDisabled);
        return Result.success(masteryService.getStudentDashboard(subject, show));
    }

    @GetMapping("/history/{knowledgePointId}")
    @Operation(summary = "获取知识点变更历史")
    public Result<PageResult<MasteryChangeLogVO>> getChangeHistory(
            @PathVariable String knowledgePointId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(masteryService.getStudentChangeHistory(knowledgePointId, page, size));
    }
}
