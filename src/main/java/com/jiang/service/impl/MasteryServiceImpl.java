package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.common.util.MasteryFormulaUtil;
import com.jiang.dto.AdminMasteryQueryRequest;
import com.jiang.mapper.GeneratedQuestionMapper;
import com.jiang.mapper.HomeworkSubmissionDetailMapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.MasteryChangeLogMapper;
import com.jiang.mapper.QuestionBankMapper;
import com.jiang.mapper.StudentKnowledgeStateMapper;
import com.jiang.model.entity.GeneratedQuestion;
import com.jiang.model.entity.HomeworkSubmissionDetail;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.MasteryChangeLog;
import com.jiang.model.entity.QuestionBank;
import com.jiang.model.entity.StudentKnowledgeState;
import com.jiang.model.enums.ChangeType;
import com.jiang.security.StudentContext;
import com.jiang.service.ErrorQuestionService;
import com.jiang.service.MasteryService;
import com.jiang.vo.MasteryChangeLogVO;
import com.jiang.vo.MasteryDashboardVO;
import com.jiang.vo.MasteryDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 掌握度核心业务 Service 实现
 *
 * @author StudyMate Team
 * @since 2026-11-02
 */
@Slf4j
@Service
public class MasteryServiceImpl implements MasteryService {

    @Autowired
    private StudentKnowledgeStateMapper stateMapper;

    @Autowired
    private MasteryChangeLogMapper changeLogMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private QuestionBankMapper questionBankMapper;

    @Autowired
    private HomeworkSubmissionDetailMapper submissionDetailMapper;

    @Autowired
    private GeneratedQuestionMapper generatedQuestionMapper;

    @Autowired
    private ErrorQuestionService errorQuestionService;

    // ===== 写操作（掌握度更新）=====

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateMasteryFromHomework(Long studentId, String tenantId, List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }
        List<HomeworkSubmissionDetail> submissions = validateSubmissionsOwnership(submissionIds, studentId, tenantId);
        Map<String, StudentKnowledgeState> stateCache = processSubmissions(submissions, studentId, tenantId);
        batchFlushStates(stateCache);
    }

    /**
     * 归属校验：验证提交记录存在且属于当前学生
     */
    private List<HomeworkSubmissionDetail> validateSubmissionsOwnership(
            List<Long> submissionIds, Long studentId, String tenantId) {
        List<HomeworkSubmissionDetail> submissions = submissionDetailMapper.selectBatchIds(submissionIds);
        if (submissions.size() != submissionIds.size()) {
            throw new BusinessException(ErrorCodes.HOMEWORK_NOT_FOUND);
        }
        for (HomeworkSubmissionDetail submission : submissions) {
            if (!studentId.equals(submission.getStudentId()) || !tenantId.equals(submission.getTenantId())) {
                throw new BusinessException(ErrorCodes.HOMEWORK_NOT_FOUND);
            }
        }
        return submissions;
    }

    /**
     * 处理提交记录：排序 → 循环计算掌握度 → 写变更日志
     *
     * @return 内存缓存的知识点状态 Map（kpId → state）
     */
    private Map<String, StudentKnowledgeState> processSubmissions(
            List<HomeworkSubmissionDetail> submissions, Long studentId, String tenantId) {
        Map<String, StudentKnowledgeState> stateCache = new HashMap<>();

        List<HomeworkSubmissionDetail> sorted = submissions.stream()
                .sorted((a, b) -> a.getSubmissionId().compareTo(b.getSubmissionId()))
                .collect(Collectors.toList());

        for (HomeworkSubmissionDetail submission : sorted) {
            String kpId = submission.getKnowledgePointId();
            boolean isCorrect = submission.getIsCorrect() != null && submission.getIsCorrect() == 1;
            BigDecimal dNorm = calculateDNorm(submission, tenantId);

            StudentKnowledgeState state = stateCache.get(kpId);
            if (state == null) {
                state = findOrCreateState(studentId, tenantId, kpId, ChangeType.HOMEWORK);
                stateCache.put(kpId, state);
            }

            BigDecimal mCurrent = state.getMasteryScore();
            BigDecimal wSource = ChangeType.HOMEWORK.getSourceWeight();
            BigDecimal confidence = state.getConfidence();

            BigDecimal mNew;
            BigDecimal delta;
            if (isCorrect) {
                mNew = MasteryFormulaUtil.calculateMasteryCorrect(mCurrent, wSource, dNorm, confidence);
                delta = mNew.subtract(mCurrent);
            } else {
                mNew = MasteryFormulaUtil.calculateMasteryIncorrect(mCurrent, wSource, dNorm, confidence);
                delta = mNew.subtract(mCurrent);
            }

            BigDecimal masteryBefore = state.getMasteryScore();
            state.setMasteryScore(mNew);
            state.setConfidence(MasteryFormulaUtil.updateConfidence(confidence));
            state.setSource(ChangeType.HOMEWORK.getCode());
            state.setLastAssessedAt(LocalDateTime.now());

            MasteryChangeLog changeLog = new MasteryChangeLog();
            changeLog.setStudentId(studentId);
            changeLog.setKnowledgePointId(kpId);
            changeLog.setChangeType(ChangeType.HOMEWORK.getCode());
            changeLog.setSourceId(submission.getSubmissionId());
            changeLog.setDelta(delta);
            changeLog.setMasteryBefore(masteryBefore);
            changeLog.setMasteryAfter(mNew);
            changeLog.setTenantId(tenantId);
            changeLogMapper.insert(changeLog);
        }
        return stateCache;
    }

    /**
     * 批量写回状态并校验乐观锁
     * T-05: 末尾追加错题联动（syncPauseStatus）
     *
     * <p>调用路径说明：
     * <ul>
     *   <li>路径 A（作业批改）：gradeHomework → updateMasteryFromHomework → batchFlushStates → 循环 syncPauseStatus</li>
     *   <li>路径 B（评测/考试）：updateMasteryFromResult → 单次 syncPauseStatus（见该方法内注释）</li>
     * </ul>
     * 两条路径互斥，不会在同一事务内重复触发 syncPauseStatus。</p>
     */
    private void batchFlushStates(Map<String, StudentKnowledgeState> stateCache) {
        // 提取 studentId/tenantId 用于错题联动（stateCache 非空时才有意义）
        Long studentId = null;
        String tenantId = null;

        for (StudentKnowledgeState state : stateCache.values()) {
            // 首次迭代提取 studentId/tenantId（同一批次内不变）
            if (studentId == null) {
                studentId = state.getStudentId();
                tenantId = state.getTenantId();
            }

            int rows = stateMapper.updateById(state);
            if (rows == 0) {
                throw new BusinessException(ErrorCodes.MASTERY_CONCURRENT_CONFLICT);
            }
        }

        // T-05: 掌握度更新后联动错题暂停/重新入队
        // IMP-3: 在同一事务内执行（batchFlushStates 由 @Transactional 方法调用）
        // 注意：当由 gradeHomework 调用时，步骤 5.5 的错题操作（recordWrongAnswer/advanceStage/resetStage）
        // 已在同一事务内先于本循环执行；本循环的 syncPauseStatus 是该事务内的第二次错题相关调用，
        // 但由于 syncPauseStatus 是幂等的（WHERE status='active'/'paused'），重复调用无副作用。
        if (studentId != null && tenantId != null) {
            for (StudentKnowledgeState state : stateCache.values()) {
                errorQuestionService.syncPauseStatus(
                        studentId, tenantId,
                        state.getKnowledgePointId(), state.getMasteryScore());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateMasteryFromResult(Long studentId, String tenantId,
                                         String knowledgePointId, ChangeType changeType,
                                         Long sourceId, boolean isCorrect, BigDecimal dNorm) {
        // 1. 查询/创建 state
        StudentKnowledgeState state = findOrCreateState(studentId, tenantId, knowledgePointId, changeType);

        // 2. 计算新掌握度
        BigDecimal mCurrent = state.getMasteryScore();
        BigDecimal wSource = changeType.getSourceWeight();
        BigDecimal confidence = state.getConfidence();

        BigDecimal mNew;
        BigDecimal delta;
        if (isCorrect) {
            mNew = MasteryFormulaUtil.calculateMasteryCorrect(mCurrent, wSource, dNorm, confidence);
            delta = mNew.subtract(mCurrent);
        } else {
            mNew = MasteryFormulaUtil.calculateMasteryIncorrect(mCurrent, wSource, dNorm, confidence);
            delta = mNew.subtract(mCurrent);
        }

        // 3. 更新 state
        BigDecimal masteryBefore = state.getMasteryScore();
        state.setMasteryScore(mNew);
        state.setConfidence(MasteryFormulaUtil.updateConfidence(confidence));
        state.setSource(changeType.getCode());
        state.setLastAssessedAt(LocalDateTime.now());

        int rows = stateMapper.updateById(state);
        if (rows == 0) {
            throw new BusinessException(ErrorCodes.MASTERY_CONCURRENT_CONFLICT);
        }

        // 路径 B: 评测/考试场景的错题联动（与路径 A batchFlushStates 互斥）
        errorQuestionService.syncPauseStatus(
                studentId, tenantId, knowledgePointId, state.getMasteryScore());

        // 4. 插入 change_log
        // 操作顺序：① 掌握度写入 → ② syncPauseStatus → ③ 变更日志
        // 依赖 @Transactional 保证三步原子性：若 ③ 失败，①② 均回滚。
        // 若后续拆分为异步/跨事务场景，需重新评估一致性（TD-CROSS-TX-CONSISTENCY）。
        MasteryChangeLog changeLog = new MasteryChangeLog();
        changeLog.setStudentId(studentId);
        changeLog.setKnowledgePointId(knowledgePointId);
        changeLog.setChangeType(changeType.getCode());
        changeLog.setSourceId(sourceId);
        changeLog.setDelta(delta);
        changeLog.setMasteryBefore(masteryBefore);
        changeLog.setMasteryAfter(mNew);
        changeLog.setTenantId(tenantId);
        changeLogMapper.insert(changeLog);
    }

    // ===== 学生端查询 =====

    @Override
    public List<MasteryDashboardVO> getStudentDashboard(String subject) {
        return getStudentDashboard(subject, false);
    }

    @Override
    public List<MasteryDashboardVO> getStudentDashboard(String subject, boolean showDisabled) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        log.info("[getStudentDashboard] studentId={}, tenantId={}, subject={}, showDisabled={}",
                studentId, tenantId, subject, showDisabled);

        // 修复 IMP-14：showDisabled 参数控制是否包含停用知识点
        List<StudentKnowledgeState> states = stateMapper.selectActiveStatesWithKpInfo(
                studentId, tenantId, subject, showDisabled);
        log.info("[getStudentDashboard] 查询结果数量: {}", states.size());
        if (states.isEmpty()) {
            return Collections.emptyList();
        }

        // 提取知识点 ID 列表，用于批量查询最新变更日志
        List<String> kpIds = states.stream()
                .map(StudentKnowledgeState::getKnowledgePointId)
                .collect(Collectors.toList());

        // 批量查询最新变更日志（用于 lastChangeDirection）
        List<MasteryChangeLog> latestLogs = changeLogMapper.selectLatestByKpIds(studentId, tenantId, kpIds);
        Map<String, MasteryChangeLog> logMap = latestLogs.stream()
                .collect(Collectors.toMap(MasteryChangeLog::getKnowledgePointId, log -> log, (a, b) -> a));

        // 组装 VO
        List<MasteryDashboardVO> result = new ArrayList<>();
        for (StudentKnowledgeState state : states) {
            MasteryChangeLog latestLog = logMap.get(state.getKnowledgePointId());

            String lastChangeDirection = null;
            if (latestLog != null && latestLog.getDelta() != null) {
                int cmp = latestLog.getDelta().compareTo(BigDecimal.ZERO);
                if (cmp > 0) {
                    lastChangeDirection = "up";
                } else if (cmp < 0) {
                    lastChangeDirection = "down";
                } else {
                    lastChangeDirection = "stable";
                }
            }

            result.add(MasteryDashboardVO.builder()
                    .knowledgePointId(state.getKnowledgePointId())
                    .knowledgePointName(state.getKpName())
                    .subject(state.getKpSubject())
                    .masteryScore(state.getMasteryScore())
                    .confidence(state.getConfidence())
                    .lastChangeDirection(lastChangeDirection)
                    .lastAssessedAt(state.getLastAssessedAt())
                    .build());
        }

        return result;
    }

    @Override
    public PageResult<MasteryChangeLogVO> getStudentChangeHistory(String knowledgePointId, int page, int size) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        long total = changeLogMapper.countHistoryByStudentAndKp(studentId, tenantId, knowledgePointId);
        if (total == 0) {
            return PageResult.of(0L, page, size, Collections.emptyList());
        }

        int offset = (page - 1) * size;
        List<MasteryChangeLog> logs = changeLogMapper.selectHistoryByStudentAndKp(
                studentId, tenantId, knowledgePointId, offset, size);
        // 批量查询知识点名称
        Map<String, KnowledgePoint> kpMap = getKpMap(tenantId, knowledgePointId);

        List<MasteryChangeLogVO> voList = logs.stream()
                .map(log -> toChangeLogVO(log, kpMap))
                .collect(Collectors.toList());

        return PageResult.of(total, page, size, voList);
    }

    // ===== 管理端查询 =====

    @Override
    public PageResult<MasteryDetailVO> getAdminDashboard(AdminMasteryQueryRequest request) {
        String tenantId = StudentContext.getTenantId();

        // 1. 若指定 subject，先获取知识点 ID 列表
        List<String> kpIds = null;
        Map<String, KnowledgePoint> kpMap = new HashMap<>();
        if (request.getSubject() != null && !request.getSubject().isBlank()) {
            List<KnowledgePoint> kps = knowledgePointMapper.selectList(
                    new LambdaQueryWrapper<KnowledgePoint>()
                            .eq(KnowledgePoint::getTenantId, tenantId)
                            .eq(KnowledgePoint::getSubject, request.getSubject())
            );
            kpIds = kps.stream().map(KnowledgePoint::getKnowledgePointId).collect(Collectors.toList());
            kpMap = kps.stream().collect(Collectors.toMap(KnowledgePoint::getKnowledgePointId, kp -> kp));
            if (kpIds.isEmpty()) {
                return PageResult.of(0L, request.getPage(), request.getSize(), Collections.emptyList());
            }
        }

        // 2. 构建查询条件（明确指定字段，避免 SELECT *）
        LambdaQueryWrapper<StudentKnowledgeState> wrapper = new LambdaQueryWrapper<StudentKnowledgeState>()
                .select(
                        StudentKnowledgeState::getStudentId,
                        StudentKnowledgeState::getKnowledgePointId,
                        StudentKnowledgeState::getMasteryScore,
                        StudentKnowledgeState::getConfidence,
                        StudentKnowledgeState::getSource,
                        StudentKnowledgeState::getLastAssessedAt
                )
                .eq(StudentKnowledgeState::getTenantId, tenantId);
        if (request.getStudentId() != null) {
            wrapper.eq(StudentKnowledgeState::getStudentId, request.getStudentId());
        }
        if (request.getKnowledgePointId() != null) {
            wrapper.eq(StudentKnowledgeState::getKnowledgePointId, request.getKnowledgePointId());
        }
        if (kpIds != null) {
            wrapper.in(StudentKnowledgeState::getKnowledgePointId, kpIds);
        }
        wrapper.orderByDesc(StudentKnowledgeState::getLastAssessedAt);

        // 3. 分页查询
        Page<StudentKnowledgeState> pageParam = new Page<>(request.getPage(), request.getSize());
        Page<StudentKnowledgeState> result = stateMapper.selectPage(pageParam, wrapper);

        // 4. 补充知识点信息（避免 N+1，一次性查询）
        List<String> resultKpIds = result.getRecords().stream()
                .map(StudentKnowledgeState::getKnowledgePointId)
                .distinct()
                .collect(Collectors.toList());
        if (!resultKpIds.isEmpty() && kpMap.isEmpty()) {
            List<KnowledgePoint> kps = knowledgePointMapper.selectBatchIds(resultKpIds);
            kpMap = kps.stream().collect(Collectors.toMap(KnowledgePoint::getKnowledgePointId, kp -> kp));
        }

        // 5. 组装 VO
        Map<String, KnowledgePoint> finalKpMap = kpMap;
        List<MasteryDetailVO> voList = result.getRecords().stream()
                .map(state -> toDetailVO(state, finalKpMap))
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), request.getPage(), request.getSize(), voList);
    }

    @Override
    public PageResult<MasteryChangeLogVO> getAdminChangeHistory(Long studentId, String knowledgePointId,
                                                                 int page, int size) {
        String tenantId = StudentContext.getTenantId();

        long total = changeLogMapper.countHistoryByStudentAndKp(studentId, tenantId, knowledgePointId);
        if (total == 0) {
            return PageResult.of(0L, page, size, Collections.emptyList());
        }

        int offset = (page - 1) * size;
        List<MasteryChangeLog> logs = changeLogMapper.selectHistoryByStudentAndKp(
                studentId, tenantId, knowledgePointId, offset, size);

        Map<String, KnowledgePoint> kpMap = getKpMap(tenantId, knowledgePointId);

        List<MasteryChangeLogVO> voList = logs.stream()
                .map(log -> toChangeLogVO(log, kpMap))
                .collect(Collectors.toList());

        return PageResult.of(total, page, size, voList);
    }

    // ===== 私有辅助方法 =====

    /**
     * 查找或创建掌握度状态记录
     */
    private StudentKnowledgeState findOrCreateState(Long studentId, String tenantId,
                                                     String knowledgePointId, ChangeType changeType) {
        LambdaQueryWrapper<StudentKnowledgeState> wrapper = new LambdaQueryWrapper<StudentKnowledgeState>()
                .eq(StudentKnowledgeState::getStudentId, studentId)
                .eq(StudentKnowledgeState::getTenantId, tenantId)
                .eq(StudentKnowledgeState::getKnowledgePointId, knowledgePointId);

        StudentKnowledgeState state = stateMapper.selectOne(wrapper);
        if (state != null) {
            return state;
        }

        // 首次创建，初始化字段
        state = new StudentKnowledgeState();
        state.setStudentId(studentId);
        state.setKnowledgePointId(knowledgePointId);
        state.setTenantId(tenantId);
        state.setMasteryScore(MasteryFormulaUtil.MASTERY_INITIAL);
        state.setConfidence(MasteryFormulaUtil.CONFIDENCE_INITIAL);
        state.setSource(changeType.getCode());
        state.setLastAssessedAt(LocalDateTime.now());
        state.setVersion(0);

        stateMapper.insert(state);
        return state;
    }

    /**
     * 计算 D_norm（含新题偏离校验）
     */
    private BigDecimal calculateDNorm(HomeworkSubmissionDetail submission, String tenantId) {
        String questionSource = submission.getQuestionSource();
        String kpId = submission.getKnowledgePointId();

        if ("bank".equals(questionSource)) {
            // 原题：从题库获取难度
            QuestionBank question = questionBankMapper.selectById(submission.getQuestionId());
            if (question == null) {
                log.warn("Question not found for submission {}, questionSource=bank, questionId={}, using D_NORM_DEFAULT",
                        submission.getSubmissionId(), submission.getQuestionId());
                return MasteryFormulaUtil.D_NORM_DEFAULT;
            }
            return MasteryFormulaUtil.mapDifficulty(question.getDifficulty());
        } else if ("generated".equals(questionSource)) {
            // 新题：需要校验偏离
            GeneratedQuestion question = generatedQuestionMapper.selectById(submission.getQuestionId());
            if (question == null) {
                log.warn("Question not found for submission {}, questionSource=generated, questionId={}, using D_NORM_DEFAULT",
                        submission.getSubmissionId(), submission.getQuestionId());
                return MasteryFormulaUtil.D_NORM_DEFAULT;
            }

            BigDecimal declaredDifficulty = MasteryFormulaUtil.mapDifficulty(question.getDifficulty());
            BigDecimal avgDifficulty = questionBankMapper.selectAvgDifficultyByKp(kpId, tenantId);

            return MasteryFormulaUtil.validateDNorm(declaredDifficulty, avgDifficulty);
        }

        return MasteryFormulaUtil.D_NORM_DEFAULT;
    }

    /**
     * 获取知识点 Map（用于填充 VO 中的知识点名称）
     */
    private Map<String, KnowledgePoint> getKpMap(String tenantId, String knowledgePointId) {
        LambdaQueryWrapper<KnowledgePoint> wrapper = new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getTenantId, tenantId)
                .eq(KnowledgePoint::getKnowledgePointId, knowledgePointId);
        List<KnowledgePoint> kps = knowledgePointMapper.selectList(wrapper);
        return kps.stream().collect(Collectors.toMap(KnowledgePoint::getKnowledgePointId, kp -> kp));
    }

    /**
     * 转换为 MasteryDetailVO
     */
    private MasteryDetailVO toDetailVO(StudentKnowledgeState state, Map<String, KnowledgePoint> kpMap) {
        KnowledgePoint kp = kpMap.get(state.getKnowledgePointId());
        return MasteryDetailVO.builder()
                .studentId(state.getStudentId())
                .knowledgePointId(state.getKnowledgePointId())
                .knowledgePointName(kp != null ? kp.getName() : null)
                .subject(kp != null ? kp.getSubject() : null)
                .kpStatus(kp != null ? kp.getStatus() : null)
                .masteryScore(state.getMasteryScore())
                .confidence(state.getConfidence())
                .source(state.getSource())
                .lastAssessedAt(state.getLastAssessedAt())
                .build();
    }

    /**
     * 转换为 MasteryChangeLogVO
     */
    private MasteryChangeLogVO toChangeLogVO(MasteryChangeLog log, Map<String, KnowledgePoint> kpMap) {
        KnowledgePoint kp = kpMap.get(log.getKnowledgePointId());
        return MasteryChangeLogVO.builder()
                .logId(log.getLogId())
                .changeType(log.getChangeType())
                .sourceId(log.getSourceId())
                .delta(log.getDelta())
                .masteryBefore(log.getMasteryBefore())
                .masteryAfter(log.getMasteryAfter())
                .knowledgePointName(kp != null ? kp.getName() : null)
                .subject(kp != null ? kp.getSubject() : null)
                .createdAt(log.getCreateTime())
                .build();
    }
}
