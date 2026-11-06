package com.jiang.service.impl;

import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.mapper.*;
import com.jiang.model.entity.*;
import com.jiang.model.enums.RecordType;
import com.jiang.security.StudentContext;
import com.jiang.service.LearningRecordService;
import com.jiang.vo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习记录服务实现
 * 修复 BLOCK-C/11 + IMP-17/18
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Service
@Slf4j
public class LearningRecordServiceImpl implements LearningRecordService {

    @Autowired
    private LearningRecordMapper learningRecordMapper;
    @Autowired
    private SubjectMapper subjectMapper;
    @Autowired
    private HomeworkSubmissionDetailMapper homeworkMapper;
    @Autowired
    private AssessmentResultMapper assessmentMapper;
    @Autowired
    private ExamAnalysisResultMapper examMapper;
    @Autowired
    private MasteryChangeLogMapper masteryChangeLogMapper;
    @Autowired
    private KnowledgePointMapper knowledgePointMapper;
    @Autowired
    private AssessmentSubmissionDetailMapper assessmentDetailMapper;

    /**
     * 修复 BLOCK-C：废弃三次归并，改用 SQL UNION ALL 统一分页
     */
    @Override
    public PageResult<LearningRecordListVO> getRecordList(LocalDate startDate, LocalDate endDate,
                                                            RecordType recordType, Integer page, Integer size) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();
        size = Math.min(size, 100); // IMP-4 分页上限

        // 指定类型：单类查询（性能最优）
        if (recordType != null) {
            return querySingleType(recordType, studentId, tenantId, startDate, endDate, page, size);
        }

        // 未指定类型：SQL UNION ALL 三类归并
        int offset = (page - 1) * size;

        // 1. 数据查询
        List<LearningRecordListVO> records = learningRecordMapper.selectUnionAllRecords(
                studentId, tenantId, startDate, endDate, offset, size);

        // 2. 总数查询
        long total = learningRecordMapper.countUnionAllRecords(
                studentId, tenantId, startDate, endDate);

        // 3. 后处理：批量查询 subjectName（防 N+1）
        fillSubjectNames(records);

        return PageResult.of(total, page, size, records);
    }

    private PageResult<LearningRecordListVO> querySingleType(RecordType type, Long studentId, String tenantId,
                                                               LocalDate startDate, LocalDate endDate,
                                                               Integer page, Integer size) {
        // 单类型查询暂时也使用 UNION ALL，后续可优化为单表查询
        int offset = (page - 1) * size;
        List<LearningRecordListVO> records = learningRecordMapper.selectUnionAllRecords(
                studentId, tenantId, startDate, endDate, offset, size);
        long total = learningRecordMapper.countUnionAllRecords(
                studentId, tenantId, startDate, endDate);

        // 按类型过滤
        records = records.stream()
                .filter(r -> r.getRecordType() == type)
                .collect(Collectors.toList());

        fillSubjectNames(records);
        return PageResult.of(total, page, size, records);
    }

    private void fillSubjectNames(List<LearningRecordListVO> records) {
        List<Long> subjectIds = records.stream()
                .map(LearningRecordListVO::getSubjectId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (!subjectIds.isEmpty()) {
            Map<Long, String> subjectNameMap = subjectMapper.selectNamesByIds(subjectIds).stream()
                    .collect(Collectors.toMap(
                            m -> ((Number) m.get("subject_id")).longValue(),
                            m -> (String) m.get("name")));
            records.forEach(r -> {
                if (r.getSubjectId() != null) {
                    r.setSubjectName(subjectNameMap.get(r.getSubjectId()));
                }
            });
        }
    }

    /**
     * 修复 BLOCK-11：详情接口按 RecordType 分派
     */
    @Override
    public LearningRecordDetailVO getRecordDetail(RecordType type, Long id) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        switch (type) {
            case HOMEWORK:
                return buildHomeworkDetail(id, studentId, tenantId);
            case ASSESSMENT:
                return buildAssessmentDetail(id, studentId, tenantId);
            case EXAM:
                return buildExamDetail(id, studentId, tenantId);
            default:
                throw new BusinessException(ErrorCodes.INVALID_RECORD_TYPE);
        }
    }

    /**
     * 修复 IMP-18：补充 buildHomeworkDetail 完整实现
     */
    private LearningRecordDetailVO buildHomeworkDetail(Long id, Long studentId, String tenantId) {
        HomeworkSubmissionDetail hw = homeworkMapper.selectByIdAndStudentId(id, studentId, tenantId);
        if (hw == null) {
            throw new BusinessException(ErrorCodes.RECORD_NOT_FOUND);
        }

        LearningRecordDetailVO vo = new LearningRecordDetailVO();
        vo.setId(id);
        vo.setRecordType(RecordType.HOMEWORK);
        vo.setRecordDate(hw.getCreateTime().toLocalDate());
        vo.setTitle("每日作业");

        HomeworkDetailVO detail = new HomeworkDetailVO();
        detail.setTotalCount(1);
        detail.setCorrectCount(hw.getIsCorrect() == 1 ? 1 : 0);
        detail.setAccuracy(hw.getIsCorrect() == 1 ? BigDecimal.ONE : BigDecimal.ZERO);
        vo.setDetail(detail);

        // 修复 IMP-17：关联掌握度变更
        vo.setMasteryChanges(getMasteryChangesBySource(id, "HOMEWORK"));

        return vo;
    }

    /**
     * 修复 Sev-5：correctCount 从 assessment_submission_detail 表统计，而非使用 score
     */
    private LearningRecordDetailVO buildAssessmentDetail(Long id, Long studentId, String tenantId) {
        AssessmentResult assessment = assessmentMapper.selectByIdAndStudentId(id, studentId, tenantId);
        if (assessment == null) {
            throw new BusinessException(ErrorCodes.RECORD_NOT_FOUND);
        }

        // 从答题明细表统计正确数
        long correctCount = assessmentDetailMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssessmentSubmissionDetail>()
                        .eq(AssessmentSubmissionDetail::getAssessmentId, id)
                        .eq(AssessmentSubmissionDetail::getTenantId, tenantId)
                        .eq(AssessmentSubmissionDetail::getIsCorrect, 1));
        long totalCount = assessmentDetailMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssessmentSubmissionDetail>()
                        .eq(AssessmentSubmissionDetail::getAssessmentId, id)
                        .eq(AssessmentSubmissionDetail::getTenantId, tenantId));

        LearningRecordDetailVO vo = new LearningRecordDetailVO();
        vo.setId(id);
        vo.setRecordType(RecordType.ASSESSMENT);
        vo.setRecordDate(assessment.getCreateTime().toLocalDate());
        vo.setTitle("专项评测");

        LearningRecordAssessmentDetailVO detail = new LearningRecordAssessmentDetailVO();
        detail.setScore(assessment.getScore());
        detail.setTotalCount((int) totalCount);
        detail.setCorrectCount((int) correctCount);
        detail.setAccuracy(totalCount > 0
                ? BigDecimal.valueOf(correctCount).divide(BigDecimal.valueOf(totalCount), 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        vo.setDetail(detail);

        vo.setMasteryChanges(getMasteryChangesBySource(id, "ASSESSMENT"));

        return vo;
    }

    private LearningRecordDetailVO buildExamDetail(Long id, Long studentId, String tenantId) {
        ExamAnalysisResult exam = examMapper.selectByIdAndStudentId(id, studentId, tenantId);
        if (exam == null) {
            throw new BusinessException(ErrorCodes.RECORD_NOT_FOUND);
        }

        LearningRecordDetailVO vo = new LearningRecordDetailVO();
        vo.setId(id);
        vo.setRecordType(RecordType.EXAM);
        vo.setRecordDate(exam.getCreateTime().toLocalDate());
        vo.setTitle(exam.getExamName());

        ExamDetailVO detail = new ExamDetailVO();
        detail.setExamName(exam.getExamName());
        detail.setUserScore(exam.getUserScore());
        detail.setTotalScore(exam.getTotalScore());
        if (exam.getTotalScore() != null && exam.getTotalScore().compareTo(BigDecimal.ZERO) > 0) {
            detail.setScoreRate(exam.getUserScore().divide(exam.getTotalScore(), 4, java.math.RoundingMode.HALF_UP));
        }
        vo.setDetail(detail);

        vo.setMasteryChanges(getMasteryChangesBySource(id, "EXAM"));

        return vo;
    }

    @Override
    public List<MasteryChangeVO> getMasteryChanges(String knowledgePointId, Integer limit) {
        Long studentId = StudentContext.getStudentId();
        String tenantId = StudentContext.getTenantId();

        List<MasteryChangeLog> changes = masteryChangeLogMapper.selectByKnowledgePointIdAndStudentId(
                knowledgePointId, studentId, tenantId, limit);

        return changes.stream()
                .map(this::toMasteryChangeVO)
                .collect(Collectors.toList());
    }

    /**
     * 修复 IMP-17：补充 getMasteryChangesBySource 实现
     */
    private List<MasteryChangeVO> getMasteryChangesBySource(Long sourceId, String changeType) {
        String tenantId = StudentContext.getTenantId();
        List<MasteryChangeLog> changes = masteryChangeLogMapper.selectBySourceIdAndType(sourceId, changeType, tenantId);
        return changes.stream().map(this::toMasteryChangeVO).collect(Collectors.toList());
    }

    /**
     * 修复 IMP-16：BigDecimal 使用 subtract()
     */
    private MasteryChangeVO toMasteryChangeVO(MasteryChangeLog log) {
        MasteryChangeVO vo = new MasteryChangeVO();
        vo.setChangeId(log.getLogId());
        vo.setKnowledgePointId(log.getKnowledgePointId());
        vo.setKnowledgePointName(getKnowledgePointName(log.getKnowledgePointId()));
        vo.setChangeType(log.getChangeType());
        vo.setSourceId(log.getSourceId());
        // 修复 IMP-16：delta 已经在 log 中存储，不需要计算
        vo.setDelta(log.getDelta());
        vo.setMasteryBefore(log.getMasteryBefore());
        vo.setMasteryAfter(log.getMasteryAfter());
        vo.setChangeTime(log.getCreateTime());
        return vo;
    }

    private String getKnowledgePointName(String knowledgePointId) {
        try {
            KnowledgePoint kp = knowledgePointMapper.selectById(knowledgePointId);
            return kp != null ? kp.getName() : knowledgePointId;
        } catch (Exception e) {
            log.warn("查询知识点名称失败: kpId={}", knowledgePointId, e);
            return knowledgePointId;
        }
    }
}
