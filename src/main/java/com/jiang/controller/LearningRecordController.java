package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.common.result.Result;
import com.jiang.model.enums.RecordType;
import com.jiang.service.LearningRecordService;
import com.jiang.vo.LearningRecordDetailVO;
import com.jiang.vo.LearningRecordListVO;
import com.jiang.vo.MasteryChangeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习记录 Controller
 * 修复 BLOCK-11：路径含 recordType
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@RestController
@RequestMapping("/api/student/records")
@Tag(name = "学习记录", description = "学习记录查询与回溯")
public class LearningRecordController {

    @Autowired
    private LearningRecordService learningRecordService;

    @GetMapping
    @Operation(summary = "查看学习记录列表")
    public Result<PageResult<LearningRecordListVO>> getRecordList(
            @RequestParam(value = "startDate", required = false) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) LocalDate endDate,
            @RequestParam(value = "recordType", required = false) String recordType,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        // 修复 BLOCK-11：recordType 枚举校验
        RecordType type = recordType != null ? RecordType.fromCode(recordType) : null;
        return Result.success(learningRecordService.getRecordList(startDate, endDate, type, page, size));
    }

    /**
     * 修复 BLOCK-11：路径改为 /records/{recordType}/{id}
     * recordType 成为路径一部分，id 语义明确
     */
    @GetMapping("/{recordType}/{id}")
    @Operation(summary = "查看学习记录详情")
    public Result<LearningRecordDetailVO> getRecordDetail(
            @PathVariable String recordType,
            @PathVariable Long id) {
        RecordType type = RecordType.fromCode(recordType);
        return Result.success(learningRecordService.getRecordDetail(type, id));
    }

    @GetMapping("/mastery-changes")
    @Operation(summary = "查看掌握度变更追溯链")
    public Result<List<MasteryChangeVO>> getMasteryChanges(
            @RequestParam String knowledgePointId,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        return Result.success(learningRecordService.getMasteryChanges(knowledgePointId, limit));
    }
}
