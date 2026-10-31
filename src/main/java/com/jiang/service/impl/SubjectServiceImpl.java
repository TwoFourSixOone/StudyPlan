package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.Subject;
import com.jiang.security.StudentContext;
import com.jiang.service.SubjectService;
import com.jiang.vo.SubjectVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 科目查询 Service 实现
 *
 * <p>M3 修正：采用单次 GROUP BY 聚合查询替代 N+1 循环查询。</p>
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Slf4j
@Service
public class SubjectServiceImpl implements SubjectService {

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private SubjectMapper subjectMapper;

    @Override
    public List<SubjectVO> getAvailableSubjects() {
        String tenantId = StudentContext.getTenantId();

        // 单次聚合查询：按 subject（科目名称）分组统计已启用知识点数量
        // 注意：KnowledgePoint.subject 是 String 类型（存科目名称），非 subjectId
        // W-02：selectMaps 中使用 AS 别名，已通过 H2 集成测试验证（164 tests pass）
        List<Map<String, Object>> counts = knowledgePointMapper.selectMaps(
            new QueryWrapper<KnowledgePoint>()
                .select("subject AS subjectName", "COUNT(*) AS cnt")
                .eq("status", "active")
                .eq("tenant_id", tenantId)
                .groupBy("subject")
        );

        // 构建 subjectName → count 映射
        Map<String, Integer> countMap = counts.stream()
            .collect(Collectors.toMap(
                m -> (String) m.get("subjectName"),
                m -> ((Number) m.get("cnt")).intValue()
            ));

        // 查询所有科目，过滤出有已启用知识点的科目
        List<Subject> subjects = subjectMapper.selectList(
            new LambdaQueryWrapper<Subject>().eq(Subject::getTenantId, tenantId)
        );

        return subjects.stream()
            .filter(s -> countMap.containsKey(s.getName()))
            .map(s -> SubjectVO.builder()
                .subjectId(s.getSubjectId())
                .name(s.getName())
                .stage(s.getStage())
                .description(s.getDescription())
                .activeKnowledgePointCount(countMap.get(s.getName()))
                .build())
            .collect(Collectors.toList());
    }
}
