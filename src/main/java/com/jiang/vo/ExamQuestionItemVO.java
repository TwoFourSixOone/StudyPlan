package com.jiang.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 试卷详情-单题（design §4.2：与作业答题区字段对齐，前端只读渲染）
 *
 * <p>零 DDL：{@code stem/choices/correctAnswer/explanation} 由
 * {@code ExamQuestionTextCodec} 从 {@code question_content} 解码得到；
 * {@code choices} 只放文本，label 由前端按序号渲染（约定 #25）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-11
 */
@Data
@Builder
public class ExamQuestionItemVO {

    /** 题目 ID（exam_question.id） */
    private Long questionId;

    /**
     * 题号：**卷面题号**（2026-11-17 起可能不连续，如缺第 16 题时为 1..15,17..19）
     *
     * <p>模型未给出可信卷面题号时回退为按图片顺序的 1..N，并在 `exam_paper.remark` 告警；
     * 存量试卷仍为 1..N。列表按本字段升序，详情页显示「第 X 题」。</p>
     */
    private Integer questionIndex;

    /** 题型：choice/numeric/short/self */
    private String questionType;

    /** 难度：basic/standard/challenge（可为 null） */
    private String difficulty;

    /** 题干（解码后的纯文本；与 questionContent 同值，兼容旧字段名） */
    private String questionContent;

    /** 题干（解码） */
    private String stem;

    /**
     * 题干富文本（2026-11-17，demo：让内联表格按表格渲染）
     *
     * <p>受控 HTML：只保留白名单标签（表格/加粗/上下标/换行等），表格带内联边框样式与 {@code min-width}，
     * 文本已转义、换行转 {@code <br/>}。与 {@link #stemBlocks} 等价（各块 html 拼接）。</p>
     */
    private String stemHtml;

    /**
     * 题干富文本分块（2026-11-17 demo：宽表格横向滚动）
     *
     * <p>text 块正常折行渲染；table 块带 {@code minWidthPx}，前端套 {@code <scroll-view scroll-x>}
     * 即可横向滚动，窄屏不再把多列表格挤成一列一列换行。</p>
     */
    private List<ExamStemBlockVO> stemBlocks;

    /** 选项文本列表（选择题；非选择题为空数组） */
    private List<String> choices;

    /** 标准答案（选择题为选项标签；缺失为 null） */
    private String correctAnswer;

    /** 解析（纯文本） */
    private String explanation;

    /** 学生作答 */
    private String studentAnswer;

    /** 是否答对：null = 无法判定/待人工 */
    private Boolean isCorrect;

    /** 得分（1 题 1 分制；无法判定为 null） */
    private BigDecimal score;

    /** 满分（1 题 1 分制；无法判定为 null） */
    private BigDecimal fullScore;

    /** 知识点 ID（待人工归因时为 null） */
    private String knowledgePointId;

    /** 知识点名称 */
    private String knowledgePointName;

    /** 归因状态：completed/pending_manual_review */
    private String attributionStatus;

    /** 作答展示态：blank（未作答）/ unreadable（无法辨识，待人工判定）/ judged（已判定） */
    private String answerState;

    /** 是否多选题（正确答案或学生答案含 ≥2 个选项标签；2026-11-12） */
    private Boolean multiChoice;

    /**
     * 题目配图 URL（2026-11-12）
     *
     * <p>模型给出图形区域时是服务端裁剪出的图形；裁剪失败时回退为「整页原图」。
     * null 表示该题无图。</p>
     */
    private String questionImageUrl;

    /**
     * 题目配图 base64（data:image/jpeg;base64,...；2026-11-12 起首选）
     *
     * <p>小程序端直接渲染 `<image :src="figureBase64 || questionImageUrl">`，不依赖文件服务地址
     * （此前 localhost URL 在真机不可达导致空白）。</p>
     */
    private String figureBase64;
}
