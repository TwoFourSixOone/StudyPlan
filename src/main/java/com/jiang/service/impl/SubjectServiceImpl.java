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

import java.util.ArrayList;
import java.util.Collections;
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

        // 单次聚合查询：按 subject（科目名称）分组统计非停用知识点数量
        // 注意：KnowledgePoint.subject 是 String 类型（存科目名称），非 subjectId
        // W-02：selectMaps 中使用 AS 别名，已通过 H2 集成测试验证（164 tests pass）
        // Demo 阶段放宽状态过滤：包含 pending_review + active（排除 disabled）
        List<Map<String, Object>> counts = knowledgePointMapper.selectMaps(
            new QueryWrapper<KnowledgePoint>()
                .select("subject AS subjectName", "COUNT(*) AS cnt")
                .ne("status", "disabled")
                .eq("tenant_id", tenantId)
                .groupBy("subject")
        );

        if (counts.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建 subjectName → count 映射
        Map<String, Integer> countMap = counts.stream()
            .collect(Collectors.toMap(
                m -> (String) m.get("subjectName"),
                m -> ((Number) m.get("cnt")).intValue()
            ));

        // 查询 subject 表
        List<Subject> subjects = subjectMapper.selectList(
            new LambdaQueryWrapper<Subject>().eq(Subject::getTenantId, tenantId)
        );

        // 自引导：如果 subject 表为空但 knowledge_point 有数据，自动创建科目记录
        if (subjects.isEmpty() && !countMap.isEmpty()) {
            log.info("[自引导] subject 表为空，从 knowledge_point 自动创建科目记录");
            for (String subjectName : countMap.keySet()) {
                Subject s = new Subject();
                s.setName(subjectName);
                s.setStage("小学");
                s.setTenantId(tenantId);
                try {
                    subjectMapper.insert(s);
                } catch (Exception e) {
                    log.warn("自动创建科目失败(可能并发): {}", subjectName, e);
                }
            }
            subjects = subjectMapper.selectList(
                new LambdaQueryWrapper<Subject>().eq(Subject::getTenantId, tenantId)
            );
        }

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
