package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.CreateGoalRequest;
import com.jiang.dto.UpdateGoalRequest;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.LearningGoalMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.LearningGoal;
import com.jiang.model.entity.Subject;
import com.jiang.model.enums.GoalStatus;
import com.jiang.security.StudentContext;
import com.jiang.service.GoalService;
import com.jiang.vo.GoalVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习目标管理 Service 实现
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Slf4j
@Service
public class GoalServiceImpl implements GoalService {

    @Autowired
    private LearningGoalMapper learningGoalMapper;

    @Autowired
    private SubjectMapper subjectMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public GoalVO createGoal(CreateGoalRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 校验科目存在
        Subject subject = subjectMapper.selectById(request.getSubjectId());
        if (subject == null || !tenantId.equals(subject.getTenantId())) {
            throw new BusinessException(ErrorCodes.SUBJECT_NOT_FOUND);
        }

        // 2. 校验科目有可用知识点（KnowledgePoint.subject 存的是科目名称，非 ID）
        // Demo 阶段放宽状态过滤：包含 pending_review + active（排除 disabled）
        long availableKpCount = knowledgePointMapper.selectCount(
                new LambdaQueryWrapper<KnowledgePoint>()
                        .eq(KnowledgePoint::getSubject, subject.getName())
                        .ne(KnowledgePoint::getStatus, "disabled")
                        .eq(KnowledgePoint::getTenantId, tenantId)
        );
        if (availableKpCount == 0) {
            throw new BusinessException(ErrorCodes.SUBJECT_NO_ACTIVE_KP);
        }

        // 3. 校验无重复活跃目标（同学生 + 同科目 + status=active）
        long activeGoalCount = learningGoalMapper.selectCount(
                new LambdaQueryWrapper<LearningGoal>()
                        .eq(LearningGoal::getStudentId, studentId)
                        .eq(LearningGoal::getSubjectId, request.getSubjectId())
                        .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
                        .eq(LearningGoal::getTenantId, tenantId)
        );
        if (activeGoalCount > 0) {
            throw new BusinessException(ErrorCodes.GOAL_ALREADY_ACTIVE);
        }

        // 4. 校验日期：endDate > startDate 且 startDate >= today
        if (!request.getEndDate().isAfter(request.getStartDate())) {
            throw new BusinessException(ErrorCodes.DATE_INVALID);
        }
        if (request.getStartDate().isBefore(LocalDate.now())) {
            throw new BusinessException(ErrorCodes.DATE_INVALID);
        }

        // 5. 创建目标
        LearningGoal goal = new LearningGoal();
        goal.setStudentId(studentId);
        goal.setSubjectId(request.getSubjectId());
        goal.setDirection(request.getDirection());
        goal.setTargetScore(request.getTargetScore());
        goal.setStartDate(request.getStartDate());
        goal.setEndDate(request.getEndDate());
        goal.setStatus(GoalStatus.ACTIVE.getCode());
        goal.setTenantId(tenantId);

        learningGoalMapper.insert(goal);

        return toGoalVO(goal, subject.getName());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public GoalVO updateGoal(Long goalId, UpdateGoalRequest request) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 1. 查询目标 + 校验所有权 + 校验可修改状态
        LearningGoal goal = findModifiableGoal(goalId, studentId, tenantId);

        // 3. 更新字段（null = 不修改，空字符串 = 清空 direction）
        if (request.getDirection() != null) {
            goal.setDirection(request.getDirection());
        }
        if (request.getTargetScore() != null) {
            goal.setTargetScore(request.getTargetScore());
        }
        if (request.getEndDate() != null) {
            // 结束日期只能延长，不能缩短
            if (request.getEndDate().isBefore(goal.getEndDate())) {
                throw new BusinessException(ErrorCodes.DATE_INVALID);
            }
            goal.setEndDate(request.getEndDate());
        }

        learningGoalMapper.updateById(goal);

        // W-01：单条更新后查询科目名称构建返回 VO，属于可接受的单次查询模式（非 N+1）
        Subject subject = subjectMapper.selectById(goal.getSubjectId());
        return toGoalVO(goal, subject != null ? subject.getName() : null);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void completeGoal(Long goalId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        // 查询目标 + 校验所有权 + 校验可修改状态
        LearningGoal goal = findModifiableGoal(goalId, studentId, tenantId);

        goal.setStatus(GoalStatus.COMPLETED.getCode());
        learningGoalMapper.updateById(goal);
    }

    @Override
    public List<GoalVO> getActiveGoals(Long subjectId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        log.info("[getActiveGoals] studentId={}, tenantId={}, subjectId={}", studentId, tenantId, subjectId);

        LambdaQueryWrapper<LearningGoal> wrapper = new LambdaQueryWrapper<LearningGoal>()
                .eq(LearningGoal::getStudentId, studentId)
                .eq(subjectId != null, LearningGoal::getSubjectId, subjectId)
                .eq(LearningGoal::getStatus, GoalStatus.ACTIVE.getCode())
                .ge(LearningGoal::getEndDate, LocalDate.now())
                .eq(LearningGoal::getTenantId, tenantId);

        wrapper.orderByDesc(LearningGoal::getCreateTime);


        List<LearningGoal> goals = learningGoalMapper.selectList(wrapper);

        log.info("[getActiveGoals] 查询结果数量: {}", goals.size());

        if (goals.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询科目信息，避免 N+1
        Set<Long> subjectIds = goals.stream()
                .map(LearningGoal::getSubjectId)
                .collect(Collectors.toSet());
        Map<Long, Subject> subjectMap = subjectIds.isEmpty() ? Collections.emptyMap() :
                subjectMapper.selectBatchIds(subjectIds).stream()
                        .collect(Collectors.toMap(Subject::getSubjectId, s -> s));

        return goals.stream()
                .map(goal -> toGoalVO(goal, subjectMap.get(goal.getSubjectId()) != null ?
                        subjectMap.get(goal.getSubjectId()).getName() : null))
                .collect(Collectors.toList());
    }

    @Override
    public PageResult<GoalVO> getGoalList(int page, int size) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        Page<LearningGoal> pageParam = new Page<>(page, size);
        Page<LearningGoal> result = learningGoalMapper.selectPage(pageParam,
                new LambdaQueryWrapper<LearningGoal>()
                        .eq(LearningGoal::getStudentId, studentId)
                        .eq(LearningGoal::getTenantId, tenantId)
                        .orderByDesc(LearningGoal::getStartDate)
        );

        List<GoalVO> voList = result.getRecords().stream()
                .map(goal -> {
                    Subject subject = subjectMapper.selectById(goal.getSubjectId());
                    return toGoalVO(goal, subject != null ? subject.getName() : null);
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    @Override
    public GoalVO getGoalDetail(Long goalId) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        LearningGoal goal = learningGoalMapper.selectById(goalId);
        // 所有权校验：studentId + tenantId 必须匹配
        if (goal == null || !studentId.equals(goal.getStudentId()) || !tenantId.equals(goal.getTenantId())) {
            throw new BusinessException(ErrorCodes.GOAL_NOT_FOUND);
        }

        Subject subject = subjectMapper.selectById(goal.getSubjectId());
        return toGoalVO(goal, subject != null ? subject.getName() : null);
    }

    @Override
    public PageResult<GoalVO> adminGetGoalList(Long studentId, Long subjectId, String status, int page, int size) {
        // N1：Demo 阶段管理端复用 StudentContext（参见 design §2.4）
        String tenantId = StudentContext.getTenantId();

        LambdaQueryWrapper<LearningGoal> wrapper = new LambdaQueryWrapper<LearningGoal>()
                .eq(LearningGoal::getTenantId, tenantId);

        if (studentId != null) {
            wrapper.eq(LearningGoal::getStudentId, studentId);
        }
        if (subjectId != null) {
            wrapper.eq(LearningGoal::getSubjectId, subjectId);
        }
        if (status != null && !status.isBlank()) {
            // W-03：校验 status 是否为合法枚举值
            try {
                GoalStatus.fromCode(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCodes.BAD_REQUEST);
            }
            wrapper.eq(LearningGoal::getStatus, status);
        }
        wrapper.orderByDesc(LearningGoal::getStartDate);

        Page<LearningGoal> pageParam = new Page<>(page, size);
        Page<LearningGoal> result = learningGoalMapper.selectPage(pageParam, wrapper);

        List<GoalVO> voList = result.getRecords().stream()
                .map(goal -> {
                    Subject subject = subjectMapper.selectById(goal.getSubjectId());
                    return toGoalVO(goal, subject != null ? subject.getName() : null);
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    @Override
    public GoalVO adminGetGoalDetail(Long goalId) {
        // N1：Demo 阶段管理端复用 StudentContext（参见 design §2.4）
        String tenantId = StudentContext.getTenantId();

        LearningGoal goal = learningGoalMapper.selectById(goalId);
        // 仅校验 tenantId，不做 studentId 所有权过滤
        if (goal == null || !tenantId.equals(goal.getTenantId())) {
            throw new BusinessException(ErrorCodes.GOAL_NOT_FOUND);
        }

        Subject subject = subjectMapper.selectById(goal.getSubjectId());
        return toGoalVO(goal, subject != null ? subject.getName() : null);
    }

    /**
     * 查找目标并校验所有权 + 可修改状态（W-07：提取公共校验逻辑）
     */
    private LearningGoal findModifiableGoal(Long goalId, Long studentId, String tenantId) {
        LearningGoal goal = learningGoalMapper.selectById(goalId);
        if (goal == null || !studentId.equals(goal.getStudentId()) || !tenantId.equals(goal.getTenantId())) {
            throw new BusinessException(ErrorCodes.GOAL_NOT_FOUND);
        }
        if (!GoalStatus.ACTIVE.getCode().equals(goal.getStatus())) {
            throw new BusinessException(ErrorCodes.GOAL_NOT_MODIFIABLE);
        }
        if (goal.getEndDate().isBefore(LocalDate.now())) {
            throw new BusinessException(ErrorCodes.GOAL_EXPIRED);
        }
        return goal;
    }

    /**
     * 将 LearningGoal 实体转换为 GoalVO
     */
    private GoalVO toGoalVO(LearningGoal goal, String subjectName) {
        return GoalVO.builder()
                .goalId(goal.getGoalId())
                .studentId(goal.getStudentId())
                .subjectId(goal.getSubjectId())
                .subjectName(subjectName)
                .direction(goal.getDirection())
                .targetScore(goal.getTargetScore())
                .startDate(goal.getStartDate())
                .endDate(goal.getEndDate())
                .status(goal.getStatus())
                .createdAt(goal.getCreateTime())
                .updatedAt(goal.getUpdateTime())
                .build();
    }
}
