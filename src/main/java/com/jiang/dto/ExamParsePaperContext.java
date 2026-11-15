package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 整卷解析上下文（2026-11-12：全部图片一次性发给视觉模型）
 *
 * <p>与单图 {@link ExamParseContext} 的差异：没有 imageSeq（模型自行标注每题所在图 imageIndex），
 * 多一个 imageTotal 用于校验 imageIndex 合法性。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-12
 */
@Data
@Builder
public class ExamParsePaperContext {

    /** 学科名（Prompt 展示） */
    private String subjectName;

    /** 学生年级中文名（约定 #23） */
    private String gradeLabel;

    /** 图片总数（1..imageTotal 供模型标注 imageIndex） */
    private int imageTotal;

    /** 租户（日志/降级用） */
    private String tenantId;

    /** 候选知识点（- ID | 名称 紧凑格式） */
    private List<KpCandidate> kpCandidates;
}
