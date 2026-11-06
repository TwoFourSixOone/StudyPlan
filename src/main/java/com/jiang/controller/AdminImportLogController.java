package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.ImportErrorVO;
import com.jiang.vo.ImportLogListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 管理端 - 导入日志 Controller
 * 修复 BLOCK-1/N1：Demo 阶段复用 StudentContext.getTenantId()
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/admin/import-logs")
@Tag(name = "导入日志管理（管理端）", description = "导入日志与错误记录查询")
public class AdminImportLogController {

    @Autowired
    private AdminListQueryService adminListQueryService;

    @GetMapping
    @Operation(summary = "导入日志列表查询")
    public Result<PageResult<ImportLogListVO>> getImportLogList(
            @RequestParam(value = "dataType", required = false) String dataType,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        size = Math.min(size, 100);
        return Result.success(adminListQueryService.getImportLogList(
                dataType, startDate, endDate, page, size));
    }

    @GetMapping("/errors")
    @Operation(summary = "导入错误记录查询")
    public Result<PageResult<ImportErrorVO>> getImportErrorList(
            @RequestParam("importId") Long importId,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        size = Math.min(size, 100);
        return Result.success(adminListQueryService.getImportErrorList(importId, page, size));
    }
}
