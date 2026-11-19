package com.jiang.service;

import com.jiang.common.result.PageResult;
import com.jiang.vo.ExamDiagnosisVO;
import com.jiang.vo.ExamImageUploadVO;
import com.jiang.vo.ExamPaperDetailVO;
import com.jiang.vo.ExamPaperListItemVO;
import com.jiang.vo.ExamSubmitVO;
import com.jiang.vo.ExamTaskProgressVO;
import com.jiang.vo.ExamUploadVO;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 试卷服务接口（Change 2026-11-11：三段式上传 + 视觉解析 + 自动判分/结算）
 *
 * <p>接口收敛到 {@code /api/student/exam/papers*}（design D9）；手工录入成绩已删除（拍板 #4）。</p>
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
public interface ExamPaperService {

    /**
     * 创建试卷上传会话（不存在则新建；30 分钟内 uploading 会话复用，超期重置后复用）
     *
     * @param subjectId  科目 ID
     * @param examDate   考试日期
     * @param paperType  试卷类型（midterm/final/monthly/mock/other，空按 midterm，非法 1191）
     * @param imageCount 声明张数（[1,8]）
     * @return 会话信息
     */
    ExamUploadVO createPaper(Long subjectId, LocalDate examDate, String paperType, Integer imageCount);

    /**
     * 追加一张试卷图片（逐张上传，单张 ≤9.5MB、累计 ≤30MB、最多 8 张）
     *
     * @param paperId 试卷 ID（必须属于当前学生 + 租户）
     * @param file    图片（multipart 字段名固定 {@code file}）
     * @return 已上传张数与状态（达到 8 张上限时自动创建解析任务）
     */
    /**
     * 追加一张试卷图片（2026-11-17：pageNo = 选择顺序 1..8，用于还原卷面页序；null 按到达顺序）
     */
    ExamImageUploadVO uploadImage(Long paperId, MultipartFile file, Integer pageNo);

    /**
     * 兼容旧调用（不传页序）
     */
    default ExamImageUploadVO uploadImage(Long paperId, MultipartFile file) {
        return uploadImage(paperId, file, null);
    }

    /**
     * 提交解析（正常触发路径；uploadedCount ≥ 1，重复提交 1186）
     *
     * @param paperId 试卷 ID
     * @return 任务信息
     */
    ExamSubmitVO submitPaper(Long paperId);

    /**
     * 重试解析（仅 {@code failed} 且整卷重试次数 < 2，复用原图）
     *
     * @param paperId 试卷 ID
     * @return 任务信息（含累计整卷重试次数）
     */
    ExamSubmitVO retryPaper(Long paperId);

    /**
     * 处理试卷解析任务（由 Worker 调用）：解析落库 → 自动判分 → 结算（错题/掌握度/学习记录）→ 异步信号
     *
     * @param taskId 任务 ID
     */
    void processExamAnalysis(Long taskId);

    /**
     * 查询解析任务进度（task → paper 归属校验，越权 1135）
     *
     * @param taskId 任务 ID
     * @return 进度信息
     */
    ExamTaskProgressVO getTaskProgress(Long taskId);

    /**
     * 历史试卷列表（design §10.1：固定 2 条 SQL，按考试日期倒序）
     *
     * @param page 页码（从 1 开始，<1 置 1）
     * @param size 每页条数（1–50，>50 夹到 50）
     * @return 分页结果（含科目名 / 题数 / 正确数 / 已判定数 / 得分）
     */
    PageResult<ExamPaperListItemVO> getPapers(Integer page, Integer size);

    /**
     * 试卷详情 + 全部题目（design §10.2：固定 3 条 SQL；他人 paperId 返回 1136，不泄漏存在性）
     *
     * @param paperId 试卷 ID
     * @return 详情（题目按题号升序；解析中/失败时题目可能为空数组，但试卷头信息照常返回）
     */
    ExamPaperDetailVO getPaperDetail(Long paperId);

    /**
     * 试卷诊断聚合（新用户引导方案 A：得分 / 正确率 / 薄弱知识点 / 错题数）
     *
     * <p>固定 4 条 SQL：① paper + 科目名（含归属校验，他人/不存在统一 1136）② 题目按卷
     * （复用 {@code selectByPaperOrderByIndex}）③ 该卷来源活跃错题数 ④ 薄弱知识点名称 +
     * 当前掌握度批量（禁止 N+1）。只读不写库、不触发 LLM。</p>
     *
     * <p>边界：试卷未解析完成 / 0 题时返回 200，各计数 0、{@code accuracy=null}，不抛异常；
     * 未判定题（is_correct=NULL）不计入正确率分母、不算答错。</p>
     *
     * @param paperId 试卷 ID
     * @return 诊断结果（薄弱知识点按 wrongCount desc、masteryScore asc 取前 10）
     */
    ExamDiagnosisVO getDiagnosis(Long paperId);
}
