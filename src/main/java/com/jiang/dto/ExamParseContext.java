package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 视觉解析上下文（拼 Prompt 用，全部为显式入参，不读 ThreadLocal）
 *
 * <p>对应 design §3.2 的占位符：{@code {subject}}、{@code {grade}}、{@code {imageSeq}}、
 * {@code {imageTotal}}、{@code {retryHint}}。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamParseContext {

    /** 学科名称（subject.name） */
    private String subjectName;

    /** 年级中文名（GradeMatcher.displayName，空值"当前"，约定 #23） */
    private String gradeLabel;

    /** 当前图片序号（从 1 开始） */
    private int imageSeq;

    /** 本卷图片总数 */
    private int imageTotal;

    /** 重试提示（首次为空/“无”） */
    private String retryHint;

    /** 租户 ID（仅用于日志/降级记录，显式传递） */
    private String tenantId;

    /**
     * 本卷候选知识点（parse 切片，v4 §3.7）：拼进 Prompt 的 {@code {knowledgePoints}}，
     * 同一卷 8 图共用同一份快照
     */
    private java.util.List<KpCandidate> kpCandidates;
}
