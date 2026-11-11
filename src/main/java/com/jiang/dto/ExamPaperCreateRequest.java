package com.jiang.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 创建试卷上传会话请求（POST /api/student/exam/papers，JSON body）
 *
 * <p>fix t23：前端 {@code student-app/src/api/exam.ts#createPaper} 以 JSON body 提交，
 * 后端原实现用 {@code @RequestParam} 只读 query/form，必然 400。统一改为 {@code @RequestBody}
 * DTO（与项目其它 POST 一致）。</p>
 *
 * <p>错误码语义保持不变：</p>
 * <ul>
 *   <li>缺字段 / 类型错误 → 400（{@code MethodArgumentNotValidException} / {@code HttpMessageNotReadableException}）</li>
 *   <li>学科不存在 → 1135</li>
 *   <li>图片张数 &gt; 8 → 1182（业务上限在 Service 层校验，保持 1182 而非 400）</li>
 *   <li>同学生+科目+日期已有试卷 → 1131</li>
 *   <li>非法 paperType → 1191（白名单在 Service 层校验）</li>
 * </ul>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
public class ExamPaperCreateRequest {

    /**
     * 科目 ID（必填）
     */
    @Schema(description = "科目 ID", example = "5")
    @NotNull(message = "科目不能为空")
    private Long subjectId;

    /**
     * 考试日期（必填，yyyy-MM-dd）
     */
    @Schema(description = "考试日期", example = "2026-11-11")
    @NotNull(message = "考试日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate examDate;

    /**
     * 试卷类型（可选：midterm/final/monthly/mock/other，非法值 1191）
     */
    @Schema(description = "试卷类型", example = "midterm")
    private String paperType;

    /**
     * 本次声明的图片张数（必填，≥1；&gt;8 由 Service 返回 1182）
     */
    @Schema(description = "图片张数（1-8）", example = "3")
    @NotNull(message = "图片张数不能为空")
    @Min(value = 1, message = "图片张数至少为 1")
    private Integer imageCount;
}
