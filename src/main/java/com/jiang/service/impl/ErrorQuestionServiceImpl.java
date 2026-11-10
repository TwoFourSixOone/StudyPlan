package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.mapper.ErrorQuestionMapper;
import com.jiang.model.entity.ErrorQuestion;
import com.jiang.model.enums.EbbinghausStage;
import com.jiang.model.enums.ErrorQuestionSourceType;
import com.jiang.service.ErrorQuestionService;
import com.jiang.vo.DueErrorQuestionVO;
import com.jiang.vo.ErrorQuestionDetailVO;
import com.jiang.vo.ErrorQuestionStatisticsVO;
import com.jiang.vo.ErrorQuestionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 错题管理 Service 实现
 *
 * <p>实现错题入队/推进/重置/暂停/重新入队/到期查询/列表查询/统计</p>
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Slf4j
@Service
public class ErrorQuestionServiceImpl implements ErrorQuestionService {

    /**
     * IMP-AH 修复：知识点数量上限，SQL LIMIT 51，Service 层 size > 50 时 WARN + subList(0, 50)
     */
    private static final int MAX_KNOWLEDGE_POINTS = 50;

    /**
     * BLOCK-H 修复：每个知识点最多取 3 道到期错题
     */
    private static final int MAX_QUESTIONS_PER_KP = 3;

    @Autowired
    private ErrorQuestionMapper errorQuestionMapper;

    // ===== 核心写操作 =====

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void recordWrongAnswer(Long studentId, String tenantId, Long questionId,
                                  String knowledgePointId,
                                  ErrorQuestionSourceType sourceType, Long sourceId,
                                  LocalDate wrongDate) {
        // 1. 构造 ErrorQuestion 对象
        ErrorQuestion errorQuestion = new ErrorQuestion();
        errorQuestion.setStudentId(studentId);
        errorQuestion.setQuestionId(questionId);
        errorQuestion.setKnowledgePointId(knowledgePointId);
        errorQuestion.setSourceType(sourceType.getCode());
        errorQuestion.setSourceId(sourceId);
        errorQuestion.setFirstWrongDate(wrongDate);   // BLOCK-F: 只在 INSERT 时写入
        errorQuestion.setLastWrongDate(wrongDate);
        errorQuestion.setCurrentStage(1);
        errorQuestion.setNextDueDate(wrongDate.plusDays(1));
        errorQuestion.setStatus("active");
        errorQuestion.setCompletedDate(null);
        errorQuestion.setTenantId(tenantId);

        // 2. 尝试 INSERT
        try {
            errorQuestionMapper.insert(errorQuestion);
            // IMP-11: 先 record 后 sync，最终状态以 sync 为准
            // BLOCK-B: 若掌握度 ≥ 0.8，后续 syncPauseStatus 立即暂停——预期行为
            return;
        } catch (DuplicateKeyException e) {
            // BLOCK-4: 捕获 DuplicateKeyException → 回退到 UPDATE（幂等）
            log.debug("[recordWrongAnswer] 并发入队，DuplicateKeyException → UPDATE fallback " +
                    "(studentId={}, questionId={})", studentId, questionId);
        }

        // 3. UPDATE 回退路径
        // BLOCK-D: 显式清空 completed_date（允许 completed → active）
        // BLOCK-F: 不修改 first_wrong_date（保留首次入队时间）
        // 审查修复：使用 LambdaUpdateWrapper.set() 显式设置 NULL，
        //   避免 MyBatis-Plus 默认 FieldStrategy.NOT_NULL 跳过 null 字段
        LambdaUpdateWrapper<ErrorQuestion> updateWrapper = new LambdaUpdateWrapper<ErrorQuestion>()
                .eq(ErrorQuestion::getStudentId, studentId)
                .eq(ErrorQuestion::getQuestionId, questionId)
                .eq(ErrorQuestion::getTenantId, tenantId)
                .set(ErrorQuestion::getLastWrongDate, wrongDate)
                .set(ErrorQuestion::getCurrentStage, 1)
                .set(ErrorQuestion::getNextDueDate, wrongDate.plusDays(1))
                .set(ErrorQuestion::getStatus, "active")
                .set(ErrorQuestion::getSourceType, sourceType.getCode())
                .set(ErrorQuestion::getSourceId, sourceId)
                .set(ErrorQuestion::getCompletedDate, null);

        errorQuestionMapper.update(null, updateWrapper);

        // 4. UPDATE 后重新查询确认记录存在
        ErrorQuestion existing = errorQuestionMapper.selectOne(
                new LambdaQueryWrapper<ErrorQuestion>()
                        .eq(ErrorQuestion::getStudentId, studentId)
                        .eq(ErrorQuestion::getQuestionId, questionId)
                        .eq(ErrorQuestion::getTenantId, tenantId));
        if (existing == null) {
            throw new BusinessException(ErrorCodes.ERROR_QUESTION_NOT_FOUND);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void advanceStage(Long studentId, String tenantId, Long questionId,
                             LocalDate correctDate) {
        // 1. 查询记录
        ErrorQuestion record = findRecord(studentId, tenantId, questionId);

        // 2. 校验 status != completed
        if ("completed".equals(record.getStatus())) {
            throw new BusinessException(ErrorCodes.ERROR_QUESTION_COMPLETED);
        }

        // 3. 计算新阶段
        int currentStage = record.getCurrentStage();
        int newStage = currentStage + 1;

        int affected;
        if (newStage > 5) {
            // stage > 5 → completed
            affected = errorQuestionMapper.completeStageWithCheck(
                    record.getErrorQuestionId(), tenantId,
                    currentStage, newStage, correctDate);
        } else {
            // stage++ → next_due_date = correctDate + interval(newStage)
            LocalDate nextDueDate = correctDate.plusDays(
                    EbbinghausStage.fromStage(newStage).getIntervalDays());
            affected = errorQuestionMapper.advanceStageWithCheck(
                    record.getErrorQuestionId(), tenantId,
                    currentStage, newStage, nextDueDate);
        }

        // IMP-A: 检查 affected rows，0 行 → 抛并发冲突
        if (affected == 0) {
            throw new BusinessException(ErrorCodes.ERROR_QUESTION_CONCURRENT_MODIFIED);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void resetStage(Long studentId, String tenantId, Long questionId,
                           LocalDate wrongDate) {
        // 1. 查询记录
        ErrorQuestion record = findRecord(studentId, tenantId, questionId);

        // 2. 校验 status != completed
        if ("completed".equals(record.getStatus())) {
            throw new BusinessException(ErrorCodes.ERROR_QUESTION_COMPLETED);
        }

        // 3. UPDATE SET current_stage=1, last_wrong_date=wrongDate,
        //    next_due_date=wrongDate+1, status='active', completed_date=NULL
        //    WHERE error_question_id=#{id} AND status != 'completed'
        //    BLOCK-F: 不修改 first_wrong_date
        // 审查修复：使用 LambdaUpdateWrapper.set() 显式设置 NULL，
        //   避免 MyBatis-Plus 默认 FieldStrategy.NOT_NULL 跳过 null 字段
        LambdaUpdateWrapper<ErrorQuestion> updateWrapper = new LambdaUpdateWrapper<ErrorQuestion>()
                .eq(ErrorQuestion::getErrorQuestionId, record.getErrorQuestionId())
                .eq(ErrorQuestion::getTenantId, tenantId)
                .ne(ErrorQuestion::getStatus, "completed")
                .set(ErrorQuestion::getCurrentStage, 1)
                .set(ErrorQuestion::getLastWrongDate, wrongDate)
                .set(ErrorQuestion::getNextDueDate, wrongDate.plusDays(1))
                .set(ErrorQuestion::getStatus, "active")
                .set(ErrorQuestion::getCompletedDate, null);

        int affected = errorQuestionMapper.update(null, updateWrapper);

        // 4. IMP-L: affected rows == 0 时重新查询判断
        if (affected == 0) {
            ErrorQuestion recheck = errorQuestionMapper.selectById(record.getErrorQuestionId());
            if (recheck == null) {
                // 并发删除窗口（极端情况）
                throw new BusinessException(ErrorCodes.ERROR_QUESTION_NOT_FOUND);
            }
            if ("completed".equals(recheck.getStatus())) {
                throw new BusinessException(ErrorCodes.ERROR_QUESTION_COMPLETED);
            }
            // IMP-X: 精确判断值未变 → 幂等成功
            if (recheck.getCurrentStage() == 1
                    && Objects.equals(recheck.getLastWrongDate(), wrongDate)
                    && Objects.equals(recheck.getNextDueDate(), wrongDate.plusDays(1))
                    && "active".equals(recheck.getStatus())) {
                log.debug("[resetStage] 幂等成功，值未变 (studentId={}, questionId={})",
                        studentId, questionId);
                return;
            }
            // 否则（status=active 但字段值不同，不应发生）
            throw new BusinessException(ErrorCodes.ERROR_QUESTION_CONCURRENT_MODIFIED);
        }
    }

    @Override
    public void syncPauseStatus(Long studentId, String tenantId,
                                String knowledgePointId, BigDecimal masteryScore) {
        // IMP-7: 批量 SQL 已有 status 条件兜底（active→paused / paused→active）
        if (masteryScore.compareTo(new BigDecimal("0.8")) >= 0) {
            // mastery >= 0.8 → 暂停 active 记录
            int affected = errorQuestionMapper.batchPauseByKnowledgePoint(
                    studentId, tenantId, knowledgePointId);
            if (affected > 0) {
                log.debug("[syncPauseStatus] 暂停 {} 条错题 (studentId={}, kpId={})",
                        affected, studentId, knowledgePointId);
            }
        } else {
            // mastery < 0.8 → 重新激活 paused 记录
            // BLOCK-2: 重新激活后 stage 保留原值
            int affected = errorQuestionMapper.batchReactivateByKnowledgePoint(
                    studentId, tenantId, knowledgePointId);
            if (affected > 0) {
                log.debug("[syncPauseStatus] 重新激活 {} 条错题 (studentId={}, kpId={})",
                        affected, studentId, knowledgePointId);
            }
        }
    }

    // ===== 查询操作 =====

    @Override
    public Map<String, List<DueErrorQuestionVO>> getDueErrorQuestions(
            Long studentId, String tenantId, LocalDate targetDate) {
        // BLOCK-H: 步骤 1 — 查询有到期错题的知识点 ID 列表
        List<String> knowledgePointIds = errorQuestionMapper.selectDueKnowledgePointIds(
                studentId, tenantId, targetDate);

        if (knowledgePointIds.isEmpty()) {
            return new LinkedHashMap<>();
        }

        // IMP-AH: SQL LIMIT 51，Service 层 size > 50 则 WARN + subList(0, 50)
        if (knowledgePointIds.size() > MAX_KNOWLEDGE_POINTS) {
            log.warn("[getDueErrorQuestions] 到期知识点数 {} 超过上限 {}，截断处理前 {} 个 (studentId={})",
                    knowledgePointIds.size(), MAX_KNOWLEDGE_POINTS, MAX_KNOWLEDGE_POINTS, studentId);
            knowledgePointIds = knowledgePointIds.subList(0, MAX_KNOWLEDGE_POINTS);
        }

        // BLOCK-H: 步骤 2 — N+1 循环，每个知识点查询最多 3 道到期错题
        // BLOCK-I: N+1 循环不纳入事务（只读查询，避免长事务）
        // 接受 READ COMMITTED 下的数据漂移
        Map<String, List<DueErrorQuestionVO>> result = new LinkedHashMap<>();

        for (String kpId : knowledgePointIds) {
            List<ErrorQuestion> questions = errorQuestionMapper.selectDueQuestionsByKnowledgePoint(
                    studentId, tenantId, kpId, targetDate);

            // BLOCK-I: 返回空 List 的知识点不放入 Map，保证 Map 的 value 非空
            if (questions == null || questions.isEmpty()) {
                continue;
            }

            // IMP-1: 转换为 DueErrorQuestionVO
            List<DueErrorQuestionVO> voList = new ArrayList<>(questions.size());
            for (ErrorQuestion q : questions) {
                DueErrorQuestionVO vo = DueErrorQuestionVO.builder()
                        .errorQuestionId(q.getErrorQuestionId())
                        .questionId(q.getQuestionId())
                        .knowledgePointId(q.getKnowledgePointId())
                        .currentStage(q.getCurrentStage())
                        .nextDueDate(q.getNextDueDate())
                        .build();
                voList.add(vo);
            }

            // IMP-AG: LinkedHashMap，key 顺序由 ORDER BY knowledge_point_id ASC 决定
            result.put(kpId, voList);
        }

        return result;
    }

    @Override
    public PageResult<ErrorQuestionVO> getErrorQuestions(Long studentId, String tenantId,
                                                         String knowledgePointId, String status,
                                                         int page, int size) {
        // IMP-F: 参数校验
        if (page < 1) {
            page = 1;
        }
        if (size > 100) {
            size = 100;
        }
        if (size < 1) {
            size = 1;
        }

        int offset = (page - 1) * size;

        // IMP-5: 使用自定义 JOIN SQL 避免 N+1
        List<Map<String, Object>> rows = errorQuestionMapper.selectPageWithDetail(
                studentId, tenantId, knowledgePointId, status, offset, size);

        int total = errorQuestionMapper.selectCount(
                studentId, tenantId, knowledgePointId, status);

        // 转换为 ErrorQuestionVO
        List<ErrorQuestionVO> voList = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ErrorQuestionVO vo = ErrorQuestionVO.builder()
                    .errorQuestionId(toLong(row.get("error_question_id")))
                    .questionId(toLong(row.get("question_id")))
                    .knowledgePointId((String) row.get("knowledge_point_id"))
                    .knowledgePointName((String) row.get("kp_name"))
                    .sourceType((String) row.get("source_type"))
                    .firstWrongDate(toLocalDate(row.get("first_wrong_date")))
                    .lastWrongDate(toLocalDate(row.get("last_wrong_date")))
                    .currentStage(toInt(row.get("current_stage")))
                    .nextDueDate(toLocalDate(row.get("next_due_date")))
                    .status((String) row.get("status"))
                    // IMP-G: 防御性兜底
                    .questionContent(row.get("question_content") != null
                            ? (String) row.get("question_content") : "[题目已删除]")
                    .questionDifficulty(row.get("question_difficulty") != null
                            ? (String) row.get("question_difficulty") : "unknown")
                    .build();
            voList.add(vo);
        }

        return PageResult.of((long) total, page, size, voList);
    }

    @Override
    public ErrorQuestionDetailVO getErrorQuestionDetail(Long studentId, String tenantId,
                                                          Long errorQuestionId) {
        // 按 ID 查询，student_id/tenant_id 作越权校验（查不到即视为不存在）
        Map<String, Object> row = errorQuestionMapper.selectDetailById(
                studentId, tenantId, errorQuestionId);
        if (row == null) {
            throw new BusinessException(ErrorCodes.ERROR_QUESTION_NOT_FOUND);
        }

        return ErrorQuestionDetailVO.builder()
                .errorQuestionId(toLong(row.get("error_question_id")))
                .questionId(toLong(row.get("question_id")))
                .knowledgePointId((String) row.get("knowledge_point_id"))
                .knowledgePointName((String) row.get("kp_name"))
                .sourceType((String) row.get("source_type"))
                .firstWrongDate(toLocalDate(row.get("first_wrong_date")))
                .lastWrongDate(toLocalDate(row.get("last_wrong_date")))
                .currentStage(toInt(row.get("current_stage")))
                .nextDueDate(toLocalDate(row.get("next_due_date")))
                .status((String) row.get("status"))
                // IMP-G 同款防御性兑底（COALESCE + 二次判空）
                .questionContent(row.get("question_content") != null
                        ? (String) row.get("question_content") : "[题目已删除]")
                .questionDifficulty(row.get("question_difficulty") != null
                        ? (String) row.get("question_difficulty") : "unknown")
                .questionType((String) row.get("question_type"))
                .options((String) row.get("question_choices"))
                .correctAnswer((String) row.get("correct_answer"))
                .analysis((String) row.get("question_analysis"))
                .build();
    }

    @Override
    public ErrorQuestionStatisticsVO getStatistics(Long studentId, String tenantId) {
        LocalDate today = LocalDate.now();

        // IMP-6: 返回 Map，Service 层转 VO
        Map<String, Object> stats = errorQuestionMapper.selectStatistics(
                studentId, tenantId, today);

        // BLOCK-C: 显式判空兜底（null → 0），防止 COALESCE 失效场景
        int totalCount = toInt(stats.get("totalCount"));
        int dueCount = toInt(stats.get("dueCount"));
        int pausedCount = toInt(stats.get("pausedCount"));

        return ErrorQuestionStatisticsVO.builder()
                .activeAndPausedCount(totalCount)
                .dueCount(dueCount)
                .pausedCount(pausedCount)
                .build();
    }

    // ===== 私有辅助方法 =====

    /**
     * 查找错题记录，不存在则抛 ERROR_QUESTION_NOT_FOUND
     */
    private ErrorQuestion findRecord(Long studentId, String tenantId, Long questionId) {
        ErrorQuestion record = errorQuestionMapper.selectOne(
                new LambdaQueryWrapper<ErrorQuestion>()
                        .eq(ErrorQuestion::getStudentId, studentId)
                        .eq(ErrorQuestion::getQuestionId, questionId)
                        .eq(ErrorQuestion::getTenantId, tenantId));
        if (record == null) {
            throw new BusinessException(ErrorCodes.ERROR_QUESTION_NOT_FOUND);
        }
        return record;
    }

    /**
     * 安全转换 Object → Long（MyBatis Map 返回值可能为 Integer/Long/BigDecimal）
     */
    private Long toLong(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Long) {
            return (Long) obj;
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return Long.parseLong(obj.toString());
    }

    /**
     * 安全转换 Object → Integer
     */
    private Integer toInt(Object obj) {
        if (obj == null) {
            return 0;
        }
        if (obj instanceof Integer) {
            return (Integer) obj;
        }
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        return Integer.parseInt(obj.toString());
    }

    /**
     * 安全转换 Object → LocalDate
     */
    private LocalDate toLocalDate(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof LocalDate) {
            return (LocalDate) obj;
        }
        return LocalDate.parse(obj.toString());
    }
}
