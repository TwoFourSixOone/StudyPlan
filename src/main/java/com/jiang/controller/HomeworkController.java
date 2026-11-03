package com.jiang.controller;

import com.jiang.common.result.Result;
import com.jiang.dto.SubmitAnswerRequest;
import com.jiang.security.StudentContext;
import com.jiang.service.HomeworkService;
import com.jiang.vo.GradeResultVO;
import com.jiang.vo.HomeworkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 学生端每日作业 Controller
 * <p>
 * BLOCK-2: 所有接口的 studentId/tenantId 均从 StudentContext（JWT 上下文）提取，不接受客户端参数传入
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@RestController
@RequestMapping("/api/homework")
@Tag(name = "学生端 - 每日作业", description = "每日作业生成、查询、提交、批改接口")
public class HomeworkController {

    @Autowired
    private HomeworkService homeworkService;

    /**
     * 生成每日作业
     * NEW-3: 新增 subjectId 参数，用于调用 GoalService.getActiveGoal(subjectId)
     */
    @PostMapping("/generate")
    @Operation(summary = "生成每日作业")
    public Result<Void> generateHomework(@RequestParam Long subjectId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        homeworkService.generateDailyHomework(studentId, tenantId, subjectId, planDate);
        return Result.success();
    }

    /**
     * 查询指定日期作业列表
     * IMP-10: 接口名从 /today 改为 /by-date
     * NEW-3: 新增 subjectId 参数
     */
    @GetMapping("/by-date")
    @Operation(summary = "查询指定日期作业列表")
    public Result<List<HomeworkVO>> getHomeworkByDate(
            @RequestParam Long subjectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        List<HomeworkVO> homework = homeworkService.getHomeworkByDate(studentId, tenantId, subjectId, planDate);
        return Result.success(homework);
    }

    /**
     * 提交单题答案
     * BLOCK-2: SubmitAnswerRequest 不含 tenantId，从 StudentContext 获取
     */
    @PostMapping("/submit")
    @Operation(summary = "提交单题答案")
    public Result<Void> submitAnswer(@RequestBody @Valid SubmitAnswerRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        homeworkService.submitAnswer(studentId, tenantId, request);
        return Result.success();
    }

    /**
     * 批改作业
     * OBS-1: 新增 subjectId 参数，用于定位 goal_id → daily_plan
     */
    @PostMapping("/grade")
    @Operation(summary = "批改作业")
    public Result<GradeResultVO> gradeHomework(@RequestParam Long subjectId,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        GradeResultVO result = homeworkService.gradeHomework(studentId, tenantId, subjectId, planDate);
        return Result.success(result);
    }
}
