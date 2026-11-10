package com.jiang.controller;

import com.jiang.common.result.Result;
import com.jiang.dto.UpdateGradeRequest;
import com.jiang.service.StudentProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生个人资料 Controller（产品规则 #11）
 *
 * @author StudyMate Team
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/api/student/profile")
@Tag(name = "学生端 - 个人资料", description = "年级设置等资料维护接口")
public class StudentProfileController {

    @Autowired
    private StudentProfileService studentProfileService;

    /**
     * 设置/修改年级
     *
     * <p>身份取自 JWT，学生只能改自己的年级。</p>
     */
    @PutMapping("/grade")
    @Operation(summary = "设置年级")
    public Result<Void> updateGrade(@RequestBody @Valid UpdateGradeRequest request) {
        studentProfileService.updateGrade(request.getGrade());
        return Result.success();
    }
}
