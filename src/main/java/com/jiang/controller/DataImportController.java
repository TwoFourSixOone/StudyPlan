package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.dto.ImportResultDTO;
import com.jiang.model.entity.DataImportErrorLog;
import com.jiang.service.DataImportService;
import com.jiang.vo.ImportLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 数据导入 Controller
 * <p>
 * 管理后端数据导入接口，需 JWT 鉴权。
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@RestController
@RequestMapping("/api/admin/import")
@Tag(name = "数据导入管理", description = "管理后端数据导入接口")
@Slf4j
public class DataImportController {

    @Autowired
    private DataImportService dataImportService;

    @PostMapping
    @Operation(summary = "执行数据导入", description = "上传 JSON 文件并执行导入，返回导入统计结果")
    public Result<ImportResultDTO> importData(
            @RequestParam("file") MultipartFile file,
            @RequestParam("dataType") String dataType) {
        ImportResultDTO result = dataImportService.importData(file, dataType);
        return Result.success(result);
    }

    @GetMapping("/logs")
    @Operation(summary = "查询导入日志", description = "分页查询导入操作日志")
    public Result<PageResult<ImportLogVO>> getImportLogs(
            @RequestParam(required = false) String dataType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<ImportLogVO> result = dataImportService.getImportLogs(dataType, page, size);
        return Result.success(result);
    }

    @GetMapping("/errors")
    @Operation(summary = "查询导入错误记录", description = "分页查询指定批次的导入错误明细")
    public Result<PageResult<DataImportErrorLog>> getImportErrors(
            @RequestParam Long importId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<DataImportErrorLog> result = dataImportService.getImportErrors(importId, page, size);
        return Result.success(result);
    }
}
