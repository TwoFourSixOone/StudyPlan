package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.AdminMasteryQueryRequest;
import com.jiang.service.MasteryService;
import com.jiang.vo.MasteryChangeLogVO;
import com.jiang.vo.MasteryDetailVO;
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
 * 管理端掌握度 Controller
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@RestController
@RequestMapping("/api/admin/mastery")
@Tag(name = "管理端 - 掌握度", description = "管理端掌握度看板与变更历史接口")
public class AdminMasteryController {

    @Autowired
    private MasteryService masteryService;

    @GetMapping("/dashboard")
    @Operation(summary = "获取掌握度看板（筛选+分页）")
    public Result<PageResult<MasteryDetailVO>> getDashboard(
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String knowledgePointId,
            @RequestParam(required = false) String subject,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        AdminMasteryQueryRequest request = AdminMasteryQueryRequest.builder()
                .studentId(studentId)
                .knowledgePointId(knowledgePointId)
                .subject(subject)
                .page(page)
                .size(size)
                .build();
        return Result.success(masteryService.getAdminDashboard(request));
    }

    @GetMapping("/student/{studentId}/history/{knowledgePointId}")
    @Operation(summary = "获取学生知识点变更历史")
    public Result<PageResult<MasteryChangeLogVO>> getChangeHistory(
            @PathVariable Long studentId,
            @PathVariable String knowledgePointId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(masteryService.getAdminChangeHistory(studentId, knowledgePointId, page, size));
    }
}
