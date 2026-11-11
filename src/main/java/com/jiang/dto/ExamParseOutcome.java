package com.jiang.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 整卷解析结果（多图合并 + 题号重排 + 跨图去重 + 归因之后）
 *
 * <p>返回的 {@link #questions} 只含结构化字段，<b>不含任何图片字节/base64</b>（design §11.3 禁止项）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamParseOutcome {

    /** 合并后的题目（题号已重排为 1..N 且升序，无法判定的题也保留） */
    private List<ParsedQuestion> questions;

    /** 解析成功的图片张数 */
    private int succeededImageCount;

    /** 解析失败的图片张数（超时/JSON 非法/读图失败） */
    private int failedImageCount;

    /** 单图切题重试次数合计（仅内存计数，不写 ocr_retry_count） */
    private int cutRetryCount;

    /** 归因重试次数合计（对应 attribution_retry_count 语义，由 Worker 落库） */
    private int attributionRetryCount;

    /** 因超过单卷题数上限被截断 */
    private boolean truncated;

    /** 跨图重复被合并的题数 */
    private int duplicateCount;

    /** 全部图片均解析失败 */
    private boolean allImagesFailed;

    /** 按图片顺序的告警（脱敏、单条 ≤200 字） */
    private List<String> imageWarnings;

    /** 直出命中知识点题数（白名单通过的解析直出，v4 §5） */
    private int directKpCount;

    /** 兜底归因补上知识点的题数 */
    private int fallbackKpCount;

    /** 最终仍无知识点的题数（pending_manual_review） */
    private int unresolvedKpCount;

    /** 年级过滤后候选总数（v4b 统计） */
    private int kpTotal;

    /** 被 parse 上限截断的候选数（v4b 统计） */
    private int kpTruncated;
}
