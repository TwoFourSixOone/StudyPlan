package com.jiang.controller;

import com.jiang.common.result.Result;
import com.jiang.service.SubjectService;
import com.jiang.vo.SubjectVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 科目查询 Controller
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@RestController
@RequestMapping("/api/student/subject")
@Tag(name = "科目管理", description = "科目查询接口")
public class SubjectController {

    @Autowired
    private SubjectService subjectService;

    @GetMapping
    @Operation(summary = "查询可用科目列表")
    public Result<List<SubjectVO>> getAvailableSubjects() {
        return Result.success(subjectService.getAvailableSubjects());
    }
}
