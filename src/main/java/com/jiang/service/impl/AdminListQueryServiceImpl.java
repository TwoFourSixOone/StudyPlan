package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiang.common.result.PageResult;
import com.jiang.mapper.*;
import com.jiang.model.entity.*;
import com.jiang.security.StudentContext;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 管理后端列表查询 Service 实现
 * 修复 IMP-1~5 + BLOCK-1/N1
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Slf4j
@Service
public class AdminListQueryServiceImpl implements AdminListQueryService {

    @Autowired
    private QuestionBankMapper questionBankMapper;
    @Autowired
    private KnowledgePointDependencyMapper dependencyMapper;
    @Autowired
    private CurriculumStandardMapper standardMapper;
    @Autowired
    private DataImportLogMapper importLogMapper;
    @Autowired
    private DataImportErrorLogMapper importErrorMapper;
    @Autowired
    private KnowledgePointMapper knowledgePointMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public PageResult<QuestionListVO> getQuestionList(String knowledgePointId, String difficulty,
                                                        String questionType, String keyword,
                                                        String subject, String stage,
                                                        Integer page, Integer size) {
        String tenantId = StudentContext.getTenantId();
        // 修复 IMP-4：size 最大限制 100
        size = Math.min(size, 100);

        Page<QuestionBank> pageParam = new Page<>(page, size);
        QueryWrapper<QuestionBank> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId);

        if (knowledgePointId != null && !knowledgePointId.isEmpty()) {
            wrapper.eq("knowledge_point_id", knowledgePointId);
        }
        if (difficulty != null && !difficulty.isEmpty()) {
            wrapper.eq("difficulty", difficulty);
        }
        // 修复 IMP-1：列名为 type 而非 question_type
        if (questionType != null && !questionType.isEmpty()) {
            wrapper.eq("type", questionType);
        }
        if (keyword != null && !keyword.isEmpty()) {
            // 修复 IMP-3：转义 % 和 _
            String escaped = keyword.replace("%", "\\%").replace("_", "\\_");
            wrapper.like("content", escaped);
        }

        // 学科和学段过滤：通过知识点表关联查询
        List<String> filteredKpIds = getFilteredKpIds(subject, stage, tenantId);
        if (filteredKpIds != null) {
            if (filteredKpIds.isEmpty()) {
                // 没有匹配的知识点，直接返回空结果
                return PageResult.of(0L, page, size, Collections.emptyList());
            }
            wrapper.in("knowledge_point_id", filteredKpIds);
        }

        wrapper.orderByDesc("create_time");

        Page<QuestionBank> result = questionBankMapper.selectPage(pageParam, wrapper);

        // 修复 IMP-5：批量查询知识点名称、学科、课程标准，避免 N+1
        // knowledgePointId 是 String 类型
        List<String> kpIds = result.getRecords().stream()
                .map(QuestionBank::getKnowledgePointId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> kpNameMap = getKpNameMap(kpIds, tenantId);

        // 批量查询学科和课程标准（用于获取 subjectName 和 stage）
        Map<String, String> kpSubjectMap = new HashMap<>();
        Map<String, String> kpStageMap;
        if (!kpIds.isEmpty()) {
            List<Map<String, Object>> kpDetails = knowledgePointMapper.selectSubjectAndStandardsByIds(kpIds, tenantId);
            for (Map<String, Object> row : kpDetails) {
                String kpId = (String) row.get("knowledge_point_id");
                String subjectVal = (String) row.get("subject");
                kpSubjectMap.put(kpId, subjectVal);
            }

            // 通过 knowledge_point 关联的 standards 查询学段
            // standards 存储的是 curriculum_standard 的 code（如 cms-2022:1.NA.01）
            // 需要通过 code 查询 curriculum_standard 表获取 stage
            if (!kpDetails.isEmpty()) {
                kpStageMap = getStageMapByKpDetails(kpDetails, tenantId);
            } else {
                kpStageMap = Collections.emptyMap();
            }
        } else {
            kpStageMap = Collections.emptyMap();
        }

        List<QuestionListVO> voList = result.getRecords().stream()
                .map(q -> {
                    QuestionListVO vo = new QuestionListVO();
                    vo.setId(q.getQuestionId());
                    vo.setContent(q.getContent());
                    vo.setKnowledgePointName(kpNameMap.getOrDefault(q.getKnowledgePointId(), "未知"));
                    vo.setDifficulty(q.getDifficulty());
                    // 修复 IMP-1：字段名为 type
                    vo.setQuestionType(q.getType());
                    vo.setAnswer(q.getAnswer());
                    vo.setAnalysis(q.getAnalysis());
                    vo.setSubjectName(kpSubjectMap.getOrDefault(q.getKnowledgePointId(), ""));
                    vo.setStage(kpStageMap.getOrDefault(q.getKnowledgePointId(), ""));
                    vo.setCreateTime(q.getCreateTime());
                    return vo;
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    @Override
    public PageResult<DependencyListVO> getDependencyList(String knowledgePointId, String prerequisiteId,
                                                            String dependencyType,
                                                            String subject, String stage,
                                                            Integer page, Integer size) {
        String tenantId = StudentContext.getTenantId();
        size = Math.min(size, 100);

        Page<KnowledgePointDependency> pageParam = new Page<>(page, size);
        QueryWrapper<KnowledgePointDependency> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId);

        if (knowledgePointId != null && !knowledgePointId.isEmpty()) {
            wrapper.eq("knowledge_point_id", knowledgePointId);
        }
        if (prerequisiteId != null && !prerequisiteId.isEmpty()) {
            wrapper.eq("prerequisite_id", prerequisiteId);
        }
        if (dependencyType != null && !dependencyType.isEmpty()) {
            wrapper.eq("dependency_type", dependencyType);
        }

        // 学科和学段过滤：通过知识点表关联查询
        List<String> filteredKpIds = getFilteredKpIds(subject, stage, tenantId);
        if (filteredKpIds != null) {
            if (filteredKpIds.isEmpty()) {
                // 没有匹配的知识点，直接返回空结果
                return PageResult.of(0L, page, size, Collections.emptyList());
            }
            // 先修关系的两个端点都要匹配过滤条件
            wrapper.and(w -> w.in("knowledge_point_id", filteredKpIds)
                    .or()
                    .in("prerequisite_id", filteredKpIds));
        }

        wrapper.orderByDesc("create_time");

        Page<KnowledgePointDependency> result = dependencyMapper.selectPage(pageParam, wrapper);

        // 修复 IMP-5：批量查询知识点名称（knowledgePointId 和 prerequisiteId 均为 String）
        List<String> allKpIds = result.getRecords().stream()
                .flatMap(d -> Stream.of(d.getKnowledgePointId(), d.getPrerequisiteId()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<String, String> kpNameMap = getKpNameMap(allKpIds, tenantId);

        List<DependencyListVO> voList = result.getRecords().stream()
                .map(d -> {
                    DependencyListVO vo = new DependencyListVO();
                    vo.setId(d.getDependencyId());
                    vo.setKnowledgePointName(kpNameMap.getOrDefault(d.getKnowledgePointId(), "未知"));
                    vo.setPrerequisiteName(kpNameMap.getOrDefault(d.getPrerequisiteId(), "未知"));
                    vo.setDependencyType(d.getDependencyType());
                    vo.setCreateTime(d.getCreateTime());
                    return vo;
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    @Override
    public PageResult<StandardListVO> getStandardList(String stage, String subject,
                                                        String keyword, Integer page, Integer size) {
        String tenantId = StudentContext.getTenantId();
        size = Math.min(size, 100);

        Page<CurriculumStandard> pageParam = new Page<>(page, size);
        QueryWrapper<CurriculumStandard> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId);

        if (stage != null && !stage.isEmpty()) {
            wrapper.eq("stage", stage);
        }
        // 修复 IMP-5：CurriculumStandard.subject 是 String 字段（非 subject_id）
        if (subject != null && !subject.isEmpty()) {
            wrapper.eq("subject", subject);
        }
        if (keyword != null && !keyword.isEmpty()) {
            // 修复 IMP-3：转义 % 和 _
            String escaped = keyword.replace("%", "\\%").replace("_", "\\_");
            wrapper.like("description", escaped);
        }

        wrapper.orderByDesc("create_time");

        Page<CurriculumStandard> result = standardMapper.selectPage(pageParam, wrapper);

        List<StandardListVO> voList = result.getRecords().stream()
                .map(s -> {
                    StandardListVO vo = new StandardListVO();
                    vo.setId(s.getStandardId());
                    vo.setCode(s.getCode());
                    vo.setDescription(s.getDescription());
                    vo.setStage(s.getStage());
                    // 修复 IMP-5：subject 是 String 字段，直接取值
                    vo.setSubjectName(s.getSubject());
                    vo.setCreateTime(s.getCreateTime());
                    return vo;
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    @Override
    public PageResult<ImportLogListVO> getImportLogList(String dataType, LocalDate startDate,
                                                          LocalDate endDate, Integer page, Integer size) {
        String tenantId = StudentContext.getTenantId();
        size = Math.min(size, 100);

        Page<DataImportLog> pageParam = new Page<>(page, size);
        QueryWrapper<DataImportLog> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId);

        if (dataType != null && !dataType.isEmpty()) {
            wrapper.eq("data_type", dataType);
        }
        if (startDate != null) {
            wrapper.ge("imported_at", startDate.atStartOfDay());
        }
        if (endDate != null) {
            wrapper.le("imported_at", endDate.atTime(23, 59, 59));
        }

        wrapper.orderByDesc("imported_at");

        Page<DataImportLog> result = importLogMapper.selectPage(pageParam, wrapper);

        List<ImportLogListVO> voList = result.getRecords().stream()
                .map(log -> {
                    ImportLogListVO vo = new ImportLogListVO();
                    vo.setId(log.getImportId());
                    vo.setDataType(log.getDataType());
                    vo.setFileSource(log.getFileSource());
                    vo.setSuccessCount(log.getSuccessCount());
                    vo.setFailCount(log.getFailCount());
                    vo.setSkipCount(log.getSkipCount());
                    vo.setImportedAt(log.getImportedAt());
                    return vo;
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    @Override
    public PageResult<ImportErrorVO> getImportErrorList(Long importId, Integer page, Integer size) {
        String tenantId = StudentContext.getTenantId();
        size = Math.min(size, 100);

        Page<DataImportErrorLog> pageParam = new Page<>(page, size);
        QueryWrapper<DataImportErrorLog> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId);
        wrapper.eq("import_id", importId);

        wrapper.orderByDesc("create_time");

        Page<DataImportErrorLog> result = importErrorMapper.selectPage(pageParam, wrapper);

        List<ImportErrorVO> voList = result.getRecords().stream()
                .map(err -> {
                    ImportErrorVO vo = new ImportErrorVO();
                    vo.setId(err.getErrorId());
                    vo.setImportId(err.getImportId());
                    vo.setRecordId(err.getRecordId());
                    vo.setErrorReason(err.getErrorReason());
                    vo.setCreateTime(err.getCreateTime());
                    return vo;
                })
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    /**
     * 修复 IMP-5：批量查询知识点名称，返回 Map<knowledgePointId, name>
     * knowledge_point_id 是 String 类型
     */
    private Map<String, String> getKpNameMap(List<String> kpIds, String tenantId) {
        if (kpIds == null || kpIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = knowledgePointMapper.selectNamesByIds(kpIds, tenantId);
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String kpId = (String) row.get("knowledge_point_id");
            String name = (String) row.get("name");
            result.put(kpId, name);
        }
        return result;
    }

    /**
     * 根据知识点的 standards JSON 批量查询学段
     * standards 存储格式：[{"id": "cms-2022:1.NA.01", "confidence": "exact"}]
     * 通过 code 查询 curriculum_standard 表获取 stage
     */
    private Map<String, String> getStageMapByKpDetails(List<Map<String, Object>> kpDetails, String tenantId) {
        Map<String, String> result = new HashMap<>();

        // 收集所有标准 code
        Set<String> allCodes = new HashSet<>();
        Map<String, List<String>> kpToCodes = new HashMap<>();
        for (Map<String, Object> row : kpDetails) {
            String kpId = (String) row.get("knowledge_point_id");
            String standardsJson = (String) row.get("standards");
            List<String> codes = new ArrayList<>();
            if (standardsJson != null && !standardsJson.isEmpty()) {
                List<Map<String, Object>> standardsList = parseStandardsJson(standardsJson, kpId);
                if (standardsList != null) {
                    for (Map<String, Object> std : standardsList) {
                        Object idObj = std.get("id");
                        if (idObj != null) {
                            String code = idObj.toString();
                            codes.add(code);
                            allCodes.add(code);
                        }
                    }
                }
            }
            kpToCodes.put(kpId, codes);
        }

        if (allCodes.isEmpty()) {
            return result;
        }

        // 批量查询 curriculum_standard 表，code → stage
        List<CurriculumStandard> standards = standardMapper.selectList(
                new QueryWrapper<CurriculumStandard>()
                        .eq("tenant_id", tenantId)
                        .in("code", allCodes)
        );
        Map<String, String> codeToStage = standards.stream()
                .collect(Collectors.toMap(CurriculumStandard::getCode, CurriculumStandard::getStage, (a, b) -> a));

        // 为每个知识点分配学段（取第一个标准的学段）
        // 降级：如果 curriculum_standard 表查不到，从 code 格式直接推导
        for (Map.Entry<String, List<String>> entry : kpToCodes.entrySet()) {
            String kpId = entry.getKey();
            for (String code : entry.getValue()) {
                String stage = codeToStage.get(code);
                if (stage == null) {
                    stage = deriveStageFromCode(code);
                }
                if (stage != null) {
                    result.put(kpId, stage);
                    break;
                }
            }
        }

        return result;
    }

    /**
     * 从课程标准 code 格式推导学段（降级方案，不依赖 curriculum_standard 表）
     * 格式：{prefix}-{year}:{stageChar}.*
     * - 冒号后首字符为数字：1~3→小学，4→初中，5→高中
     * - 冒号后首字符为字母：根据学科前缀推断（chs/cks/cbs/cgs→初中）
     * - 含 ":H." 或 "hs-"：高中
     */
    private String deriveStageFromCode(String code) {
        if (code == null) return null;
        if (code.contains(":H.") || code.contains("hs-")) {
            return "高中";
        }
        int colonIdx = code.indexOf(':');
        if (colonIdx >= 0 && colonIdx + 1 < code.length()) {
            char stageChar = code.charAt(colonIdx + 1);
            if (stageChar >= '1' && stageChar <= '3') return "小学";
            if (stageChar == '4') return "初中";
            if (stageChar == '5') return "高中";
        }
        // 字母前缀学科：历史/化学/生物/地理 默认初中
        String prefix = extractPrefix(code);
        if (Set.of("chs", "cks", "cbs", "cgs").contains(prefix)) {
            return "初中";
        }
        return null;
    }

    /**
     * 从 code 中提取学科前缀（去掉版本号和冒号后的部分）
     * 如 "chs-2022:CA.01" → "chs"
     */
    private String extractPrefix(String code) {
        if (code == null) return null;
        int colonIdx = code.indexOf(':');
        String beforeColon = colonIdx > 0 ? code.substring(0, colonIdx) : code;
        int dashIdx = beforeColon.lastIndexOf('-');
        if (dashIdx > 0) {
            beforeColon = beforeColon.substring(0, dashIdx);
        }
        if (beforeColon.endsWith("-hs")) {
            beforeColon = beforeColon.substring(0, beforeColon.length() - 3);
        }
        return beforeColon;
    }

    /**
     * 根据学科和学段过滤条件获取知识点 ID 列表
     * @return null 表示无过滤条件，空列表表示无匹配结果，非空列表为匹配的知识点 ID
     */
    private List<String> getFilteredKpIds(String subject, String stage, String tenantId) {
        boolean hasSubject = subject != null && !subject.isEmpty();
        boolean hasStage = stage != null && !stage.isEmpty();

        if (!hasSubject && !hasStage) {
            return null; // 无过滤条件
        }

        if (hasSubject && hasStage) {
            // 学科+学段联合过滤：SQL 只按学科过滤（standards 双重序列化导致 JSON_CONTAINS 不可靠），
            // 学段过滤在 Java 层通过解析 standards JSON 完成
            List<String> subjectKpIds = knowledgePointMapper.selectIdsBySubject(subject, tenantId);
            if (subjectKpIds.isEmpty()) {
                return subjectKpIds;
            }
            // 查询这些知识点的 standards，按学段二次过滤
            List<Map<String, Object>> kpDetails = knowledgePointMapper.selectSubjectAndStandardsByIds(subjectKpIds, tenantId);
            List<String> result = new ArrayList<>();
            for (Map<String, Object> row : kpDetails) {
                String kpId = (String) row.get("knowledge_point_id");
                String standardsJson = (String) row.get("standards");
                if (standardsJson != null && !standardsJson.isEmpty()) {
                    List<Map<String, Object>> standardsList = parseStandardsJson(standardsJson, kpId);
                    if (standardsList != null) {
                        for (Map<String, Object> std : standardsList) {
                            Object idObj = std.get("id");
                            if (idObj != null) {
                                String derivedStage = deriveStageFromCode(idObj.toString());
                                if (stage.equals(derivedStage)) {
                                    result.add(kpId);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            return result;
        } else if (hasSubject) {
            // 仅按学科过滤
            return knowledgePointMapper.selectIdsBySubject(subject, tenantId);
        } else {
            // 仅按学段过滤：需要查询所有学科但匹配特定学段的知识点
            // 由于没有单独的按学段查询方法，这里通过查询所有知识点然后在内存中过滤
            // 但更高效的方式是查询 curriculum_standard 表获取该学段的所有 code，
            // 然后反查知识点
            List<String> stageKpIds = new ArrayList<>();
            // 查询该学段的所有课程标准 code
            List<CurriculumStandard> standards = standardMapper.selectList(
                    new QueryWrapper<CurriculumStandard>()
                            .eq("tenant_id", tenantId)
                            .eq("stage", stage)
                            .select("code")
            );
            if (standards.isEmpty()) {
                return stageKpIds;
            }
            Set<String> codes = standards.stream()
                    .map(CurriculumStandard::getCode)
                    .collect(Collectors.toSet());
            // 查询所有知识点，过滤出 standards 中包含该学段 code 的知识点
            List<KnowledgePoint> allKps = knowledgePointMapper.selectList(
                    new QueryWrapper<KnowledgePoint>()
                            .eq("tenant_id", tenantId)
                            .select("knowledge_point_id", "standards")
            );
            for (KnowledgePoint kp : allKps) {
                if (kp.getStandards() != null && !kp.getStandards().isEmpty()) {
                    List<Map<String, Object>> stdList = parseStandardsJson(kp.getStandards(), kp.getKnowledgePointId());
                    if (stdList == null) continue;
                    for (Map<String, Object> std : stdList) {
                        Object idObj = std.get("id");
                        if (idObj != null && codes.contains(idObj.toString())) {
                            stageKpIds.add(kp.getKnowledgePointId());
                            break;
                        }
                    }
                }
            }
            return stageKpIds;
        }
    }

    /**
     * 解析知识点 standards JSON，兼容双重序列化
     * 正常格式：[{"id":"cms-2022:1.NA.01",...}]
     * 双重序列化："[{\"id\":\"cms-2022:1.NA.01\",...}]"
     */
    private List<Map<String, Object>> parseStandardsJson(String standardsJson, String kpId) {
        try {
            String trimmed = standardsJson.trim();
            if (trimmed.startsWith("[")) {
                // 正常情况：直接是 JSON 数组
                return objectMapper.readValue(standardsJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } else if (trimmed.startsWith("\"")) {
                // 双重序列化：外层是 JSON 字符串，需要二次解析
                String innerJson = objectMapper.readValue(standardsJson, String.class);
                return objectMapper.readValue(innerJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } else {
                log.warn("知识点 standards JSON 格式异常: kpId={}, value={}", kpId,
                        trimmed.substring(0, Math.min(100, trimmed.length())));
                return null;
            }
        } catch (Exception e) {
            log.warn("解析知识点 standards JSON 失败: kpId={}, error={}", kpId, e.getMessage());
            return null;
        }
    }
}
