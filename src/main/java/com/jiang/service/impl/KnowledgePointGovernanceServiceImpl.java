package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.mapper.*;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.KnowledgePointDependency;
import com.jiang.security.StudentContext;
import com.jiang.service.KnowledgePointGovernanceService;
import com.jiang.vo.KnowledgePointDetailVO;
import com.jiang.vo.KnowledgePointListVO;
import com.jiang.vo.KnowledgePointOverdueVO;
import com.jiang.vo.PrerequisiteVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识点治理服务实现
 * 修复 BLOCK-8/9 + IMP-12/27
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Service
@Slf4j
public class KnowledgePointGovernanceServiceImpl implements KnowledgePointGovernanceService {

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;
    @Autowired
    private QuestionBankMapper questionBankMapper;
    @Autowired
    private LearningGoalMapper learningGoalMapper;
    @Autowired
    private KnowledgePointDependencyMapper dependencyMapper;

    @Override
    public PageResult<KnowledgePointListVO> getKnowledgePointList(String subject, String status,
                                                                    String keyword, Integer page, Integer size) {
        // BLOCK-1/N1：Demo 阶段复用 StudentContext
        String tenantId = StudentContext.getTenantId();

        // BLOCK-8：使用 MyBatis-Plus 标准 selectPage + QueryWrapper
        Page<KnowledgePoint> pageParam = new Page<>(page, size);
        QueryWrapper<KnowledgePoint> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId);

        // IMP-27：knowledge_point.subject 是 String 类型
        if (subject != null && !subject.isEmpty()) {
            wrapper.eq("subject", subject);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq("status", status);
        }
        if (keyword != null && !keyword.isEmpty()) {
            // IMP-3：转义 % 和 _
            String escaped = keyword.replace("%", "\\%").replace("_", "\\_");
            wrapper.like("name", escaped);
        }

        wrapper.orderByDesc("create_time");

        Page<KnowledgePoint> result = knowledgePointMapper.selectPage(pageParam, wrapper);

        // IMP-12：批量查询 questionCount，避免 N+1
        List<String> kpIds = result.getRecords().stream()
                .map(KnowledgePoint::getKnowledgePointId).collect(Collectors.toList());
        Map<String, Integer> questionCountMap = getQuestionCountMap(kpIds, tenantId);

        // 批量查询 dependencyCount（先修关系数量）
        Map<String, Integer> dependencyCountMap = getDependencyCountMap(kpIds, tenantId);

        List<KnowledgePointListVO> voList = result.getRecords().stream()
                .map(kp -> {
                    KnowledgePointListVO vo = new KnowledgePointListVO();
                    vo.setId(kp.getKnowledgePointId());
                    vo.setName(kp.getName());
                    // IMP-27：KnowledgePoint.subject 直接存储科目名称
                    vo.setSubjectName(kp.getSubject());
                    vo.setStatus(kp.getStatus());
                    vo.setQuestionCount(questionCountMap.getOrDefault(kp.getKnowledgePointId(), 0));
                    vo.setDependencyCount(dependencyCountMap.getOrDefault(kp.getKnowledgePointId(), 0));
                    vo.setCreateTime(kp.getCreateTime());
                    return vo;
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    private Map<String, Integer> getQuestionCountMap(List<String> kpIds, String tenantId) {
        if (kpIds == null || kpIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> counts = questionBankMapper.countByKnowledgePointIds(kpIds, tenantId);
        Map<String, Integer> result = new HashMap<>();
        for (Map<String, Object> map : counts) {
            String kpId = (String) map.get("knowledge_point_id");
            Integer count = ((Number) map.get("cnt")).intValue();
            result.put(kpId, count);
        }
        return result;
    }

    /**
     * 批量查询每个知识点的先修关系数量
     * 包括作为 knowledge_point（有先修）和作为 prerequisite（被依赖）两个方向
     */
    private Map<String, Integer> getDependencyCountMap(List<String> kpIds, String tenantId) {
        if (kpIds == null || kpIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new HashMap<>();

        // 作为 knowledge_point 的关系数量
        List<Map<String, Object>> asKp = dependencyMapper.countAsKnowledgePoint(kpIds, tenantId);
        for (Map<String, Object> map : asKp) {
            String kpId = (String) map.get("knowledge_point_id");
            Integer count = ((Number) map.get("cnt")).intValue();
            result.merge(kpId, count, Integer::sum);
        }

        // 作为 prerequisite 被依赖的关系数量
        List<Map<String, Object>> asPrereq = dependencyMapper.countAsPrerequisite(kpIds, tenantId);
        for (Map<String, Object> map : asPrereq) {
            String kpId = (String) map.get("prerequisite_id");
            Integer count = ((Number) map.get("cnt")).intValue();
            result.merge(kpId, count, Integer::sum);
        }

        return result;
    }

    @Override
    public KnowledgePointDetailVO getKnowledgePointDetail(String id) {
        String tenantId = StudentContext.getTenantId();
        KnowledgePoint kp = knowledgePointMapper.selectByIdWithTenant(id, tenantId);
        if (kp == null) {
            throw new BusinessException(ErrorCodes.KNOWLEDGE_POINT_NOT_FOUND);
        }

        KnowledgePointDetailVO vo = new KnowledgePointDetailVO();
        vo.setId(kp.getKnowledgePointId());
        vo.setName(kp.getName());
        vo.setSubjectName(kp.getSubject());
        vo.setStatus(kp.getStatus());
        vo.setLevel(kp.getLevel());
        vo.setParentId(kp.getParentId());
        vo.setCreateTime(kp.getCreateTime());

        // 查询先修关系
        vo.setPrerequisites(getPrerequisites(id, tenantId, "prerequisite"));
        vo.setDependents(getPrerequisites(id, tenantId, "dependent"));

        // 查询题目数量
        Map<String, Integer> countMap = getQuestionCountMap(List.of(id), tenantId);
        vo.setQuestionCount(countMap.getOrDefault(id, 0));

        // TODO: studentCount 需要后续实现
        vo.setStudentCount(0);

        return vo;
    }

    private List<PrerequisiteVO> getPrerequisites(String kpId, String tenantId, String type) {
        List<KnowledgePointDependency> deps = dependencyMapper.selectByKnowledgePointId(kpId, tenantId);

        // 修复 Issue-2：批量查询知识点名称，避免 N+1
        List<String> relatedIds = deps.stream()
                .filter(d -> "prerequisite".equals(type) ? d.getKnowledgePointId().equals(kpId) : d.getPrerequisiteId().equals(kpId))
                .map(d -> "prerequisite".equals(type) ? d.getPrerequisiteId() : d.getKnowledgePointId())
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> kpNameMap = relatedIds.isEmpty()
                ? Collections.emptyMap()
                : knowledgePointMapper.selectNamesByIds(relatedIds, tenantId).stream()
                        .collect(Collectors.toMap(
                                m -> (String) m.get("knowledge_point_id"),
                                m -> (String) m.get("name")));

        return deps.stream()
                .filter(d -> "prerequisite".equals(type) ? d.getKnowledgePointId().equals(kpId) : d.getPrerequisiteId().equals(kpId))
                .map(d -> {
                    PrerequisiteVO vo = new PrerequisiteVO();
                    String relatedId = "prerequisite".equals(type) ? d.getPrerequisiteId() : d.getKnowledgePointId();
                    vo.setKnowledgePointId(relatedId);
                    vo.setDependencyType(d.getDependencyType());
                    vo.setKnowledgePointName(kpNameMap.getOrDefault(relatedId, relatedId));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String id, String status) {
        String tenantId = StudentContext.getTenantId();
        KnowledgePoint kp = knowledgePointMapper.selectByIdWithTenant(id, tenantId);
        if (kp == null) {
            throw new BusinessException(ErrorCodes.KNOWLEDGE_POINT_NOT_FOUND);
        }

        // 校验状态合法性
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw new BusinessException(ErrorCodes.KP_INVALID_STATUS);
        }

        // 停用前校验是否有活跃学习目标引用
        if ("disabled".equals(status)) {
            int activeGoalCount = learningGoalMapper.countByKnowledgePointId(id, tenantId);
            if (activeGoalCount > 0) {
                throw new BusinessException(ErrorCodes.KP_IN_USE);
            }
        }

        kp.setStatus(status);
        kp.setUpdateTime(LocalDateTime.now());
        knowledgePointMapper.updateById(kp);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsReviewed(String id) {
        String tenantId = StudentContext.getTenantId();
        KnowledgePoint kp = knowledgePointMapper.selectByIdWithTenant(id, tenantId);
        if (kp == null) {
            throw new BusinessException(ErrorCodes.KNOWLEDGE_POINT_NOT_FOUND);
        }

        // BLOCK-9：幂等处理
        if ("active".equals(kp.getStatus())) {
            // 已是 active，重复标记直接返回成功（幂等）
            return;
        }
        if (!"pending_review".equals(kp.getStatus())) {
            // disabled 或其他状态不允许标记
            throw new BusinessException(ErrorCodes.KP_NOT_PENDING_REVIEW);
        }

        kp.setStatus("active");
        kp.setUpdateTime(LocalDateTime.now());
        knowledgePointMapper.updateById(kp);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchMarkAsReviewed(List<String> ids) {
        String tenantId = StudentContext.getTenantId();
        List<KnowledgePoint> targets;

        if (ids == null || ids.isEmpty()) {
            // 空列表：查询所有待校对的知识点
            QueryWrapper<KnowledgePoint> wrapper = new QueryWrapper<>();
            wrapper.eq("tenant_id", tenantId);
            wrapper.eq("status", "pending_review");
            targets = knowledgePointMapper.selectList(wrapper);
        } else {
            // 指定 ID 列表：只校验指定的知识点（且状态为 pending_review）
            QueryWrapper<KnowledgePoint> wrapper = new QueryWrapper<>();
            wrapper.eq("tenant_id", tenantId);
            wrapper.in("knowledge_point_id", ids);
            wrapper.eq("status", "pending_review");
            targets = knowledgePointMapper.selectList(wrapper);
        }

        if (targets.isEmpty()) {
            return 0;
        }

        // 批量更新状态为 active
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (KnowledgePoint kp : targets) {
            kp.setStatus("active");
            kp.setUpdateTime(now);
            knowledgePointMapper.updateById(kp);
            count++;
        }

        return count;
    }

    @Override
    public List<KnowledgePointOverdueVO> getOverdueKnowledgePoints() {
        String tenantId = StudentContext.getTenantId();
        // IMP-13：统一为 7 个自然日
        LocalDate overdueDate = LocalDate.now().minusDays(7);

        List<KnowledgePoint> overdueList = knowledgePointMapper.selectOverdue(overdueDate, tenantId);

        return overdueList.stream()
                .map(kp -> {
                    KnowledgePointOverdueVO vo = new KnowledgePointOverdueVO();
                    vo.setId(kp.getKnowledgePointId());
                    vo.setName(kp.getName());
                    // IMP-27：直接取 kp.getSubject()
                    vo.setSubjectName(kp.getSubject());
                    vo.setCreateTime(kp.getCreateTime());
                    vo.setOverdueDays((int) ChronoUnit.DAYS.between(kp.getCreateTime().toLocalDate(), LocalDate.now()));
                    return vo;
                })
                .collect(Collectors.toList());
    }
}
