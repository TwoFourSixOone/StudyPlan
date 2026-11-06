package com.jiang.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.result.PageResult;
import com.jiang.dto.ImportResultDTO;
import com.jiang.mapper.*;
import com.jiang.model.entity.*;
import com.jiang.service.DataImportService;
import com.jiang.vo.ImportLogVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据导入 Service 实现
 * <p>
 * 事务策略：主流程不用大事务，每条记录用 TransactionTemplate 独立事务；
 * 错误日志/导入日志各自独立事务写入。
 * <p>
 * 并发控制：Demo 阶段专用锁对象，生产环境需改为分布式锁。
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@Service
@Slf4j
public class DataImportServiceImpl implements DataImportService {

    /** 导入操作专用锁对象（避免 synchronized(this) 在 AOP 代理场景下失效） */
    private final Object importLock = new Object();

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Autowired
    private QuestionBankMapper questionBankMapper;

    @Autowired
    private KnowledgePointDependencyMapper dependencyMapper;

    @Autowired
    private CurriculumStandardMapper curriculumStandardMapper;

    @Autowired
    private DataImportLogMapper importLogMapper;

    @Autowired
    private DataImportErrorLogMapper errorLogMapper;

    @Autowired
    private SubjectMapper subjectMapper;

    // 文件路径映射
    private static final Map<String, String> FILE_PATH_MAP = Map.of(
            "topic", "json/topics.json",
            "question", "json/questions.json",
            "dependency", "json/dependencies.json",
            "standard", "json/cn-curriculum-standards.json"
    );

    // 课程标准批量提交大小
    private static final int STANDARD_BATCH_SIZE = 500;

    @Override
    public ImportResultDTO importData(MultipartFile file, String dataType) {
        // 并发控制：Demo 阶段方法级 synchronized（使用专用锁对象）
        synchronized (importLock) {
            return doImport(file, dataType);
        }
    }

    // ========================= 导入核心 =========================

    private ImportResultDTO doImport(MultipartFile file, String dataType) {
        if (dataType == null) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "不支持的数据类型: null");
        }
        String filePath = FILE_PATH_MAP.get(dataType);
        if (filePath == null) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "不支持的数据类型: " + dataType);
        }

        // 1. 从上传文件读取 JSON 内容
        JsonNode jsonRoot = readJsonFromMultipartFile(file);

        // 2. 创建导入日志（初始计数=0，纳入独立事务）
        DataImportLog importLog = new DataImportLog();
        importLog.setDataType(dataType);
        importLog.setFileSource(file.getOriginalFilename()); // 使用上传文件名
        importLog.setSuccessCount(0);
        importLog.setFailCount(0);
        importLog.setSkipCount(0);
        importLog.setImportedAt(LocalDateTime.now());
        transactionTemplate.executeWithoutResult(status -> {
            importLogMapper.insert(importLog);
        });

        // 3. 预加载外键集合（用于外键校验）
        Set<String> validKpIds = loadValidKnowledgePointIds();

        // 4. 逐条校验并导入
        int successCount = 0, failCount = 0, skipCount = 0;
        List<DataImportErrorLog> errors = new ArrayList<>();
        List<String> errorSamples = new ArrayList<>();

        if ("standard".equals(dataType)) {
            // 课程标准使用批量策略
            JsonNode items = jsonRoot.get("items");
            if (items == null || !items.isArray()) {
                throw new BusinessException(ErrorCodes.IMPORT_FORMAT_ERROR, "课程标准 JSON 缺少 items 数组");
            }

            // 顶层 source 元数据序列化存入 fileSource（注意 VARCHAR(500) 限制）
            JsonNode source = jsonRoot.get("source");
            if (source != null) {
                try {
                    String sourceJson = objectMapper.writeValueAsString(source);
                    // 预留 " | source: " (11字符) 和 "..." (3字符) 的空间
                    int maxSourceLen = Math.max(50, 500 - filePath.length() - 15);
                    importLog.setFileSource(filePath + " | source: " + truncate(sourceJson, maxSourceLen));
                } catch (Exception e) {
                    log.warn("序列化 source 元数据失败", e);
                }
            }

            List<CurriculumStandard> batch = new ArrayList<>();
            for (JsonNode item : items) {
                ValidationResult vr = validateStandard(item);
                if (!vr.valid) {
                    failCount++;
                    addError(errors, importLog.getImportId(), vr.recordId, vr.message);
                    if (errorSamples.size() < 5) errorSamples.add(formatSample(vr.recordId, vr.message));
                    continue;
                }
                batch.add(mapToCurriculumStandard(item));
                if (batch.size() >= STANDARD_BATCH_SIZE) {
                    int[] result = batchInsertStandards(batch, importLog.getImportId(), errors, errorSamples);
                    successCount += result[0];
                    skipCount += result[1];
                    failCount += result[2];
                    log.info("课程标准批量导入进度: {}/{}", successCount + skipCount + failCount, items.size());
                    batch.clear();
                }
            }
            // 处理剩余
            if (!batch.isEmpty()) {
                int[] result = batchInsertStandards(batch, importLog.getImportId(), errors, errorSamples);
                successCount += result[0];
                skipCount += result[1];
                failCount += result[2];
            }
        } else {
            // 其他类型逐条处理
            List<?> records = parseRecords(jsonRoot, dataType);
            for (Object record : records) {
                JsonNode node = (JsonNode) record;
                ValidationResult vr = validate(node, dataType, validKpIds);
                if (!vr.valid) {
                    failCount++;
                    addError(errors, importLog.getImportId(), vr.recordId, vr.message);
                    if (errorSamples.size() < 5) errorSamples.add(formatSample(vr.recordId, vr.message));
                    continue;
                }

                try {
                    final JsonNode finalNode = node;
                    final String finalType = dataType;
                    transactionTemplate.executeWithoutResult(status -> {
                        insertRecord(finalNode, finalType, validKpIds);
                    });
                    successCount++;
                } catch (DuplicateKeyException e) {
                    skipCount++;
                    log.warn("ID 冲突，跳过: dataType={}, id={}", dataType, getNodeId(node, dataType));
                } catch (DataIntegrityViolationException e) {
                    failCount++;
                    String recordId = getNodeId(node, dataType);
                    // 记录实际异常信息以便定位问题（截断到 500 字符适配 error_reason 字段）
                    String rootCause = extractRootCause(e);
                    String msg = "数据完整性约束违反: " + rootCause;
                    log.error("导入记录数据完整性异常: dataType={}, recordId={}, cause={}", dataType, recordId, rootCause, e);
                    addError(errors, importLog.getImportId(), recordId, msg);
                    if (errorSamples.size() < 5) errorSamples.add(formatSample(recordId, msg));
                } catch (Exception e) {
                    failCount++;
                    String recordId = getNodeId(node, dataType);
                    addError(errors, importLog.getImportId(), recordId, e.getMessage());
                    if (errorSamples.size() < 5) errorSamples.add(formatSample(recordId, e.getMessage()));
                }
            }
        }

        // 5. 批量写入错误记录（独立事务）
        if (!errors.isEmpty()) {
            transactionTemplate.executeWithoutResult(status -> {
                for (DataImportErrorLog error : errors) {
                    errorLogMapper.insert(error);
                }
            });
        }

        // 6. 更新导入日志（独立事务）
        final int finalSuccess = successCount;
        final int finalFail = failCount;
        final int finalSkip = skipCount;
        transactionTemplate.executeWithoutResult(status -> {
            importLog.setSuccessCount(finalSuccess);
            importLog.setFailCount(finalFail);
            importLog.setSkipCount(finalSkip);
            importLogMapper.updateById(importLog);
        });

        return ImportResultDTO.builder()
                .importId(importLog.getImportId())
                .dataType(dataType)
                .fileSource(filePath)
                .successCount(successCount)
                .failCount(failCount)
                .skipCount(skipCount)
                .errorSamples(errorSamples)
                .importedAt(LocalDateTime.now())
                .build();
    }

    // ========================= JSON 读取 =========================

    /**
     * 从上传的 MultipartFile 中读取 JSON 内容
     */
    private JsonNode readJsonFromMultipartFile(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return objectMapper.readTree(is);
        } catch (Exception e) {
            log.error("读取上传 JSON 文件失败: {}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCodes.IMPORT_FILE_NOT_FOUND, "上传文件读取失败: " + file.getOriginalFilename());
        }
    }

    /**
     * 从 classpath 读取 JSON 文件（保留用于向后兼容或特殊场景）
     */
    private JsonNode readJsonFile(String filePath) {
        try {
            ClassPathResource resource = new ClassPathResource(filePath);
            try (InputStream is = resource.getInputStream()) {
                return objectMapper.readTree(is);
            }
        } catch (Exception e) {
            log.error("读取 JSON 文件失败: {}", filePath, e);
            throw new BusinessException(ErrorCodes.IMPORT_FILE_NOT_FOUND, "导入文件读取失败: " + filePath);
        }
    }

    private List<JsonNode> parseRecords(JsonNode root, String dataType) {
        if (!root.isArray()) {
            throw new BusinessException(ErrorCodes.IMPORT_FORMAT_ERROR, "JSON 文件格式错误，期望数组: " + dataType);
        }
        List<JsonNode> records = new ArrayList<>();
        root.forEach(records::add);
        return records;
    }

    // ========================= 外键预查 =========================

    private Set<String> loadValidKnowledgePointIds() {
        Set<String> ids = new HashSet<>();
        try {
            List<KnowledgePoint> all = knowledgePointMapper.selectList(
                    new LambdaQueryWrapper<KnowledgePoint>().select(KnowledgePoint::getKnowledgePointId));
            all.forEach(kp -> ids.add(kp.getKnowledgePointId()));
        } catch (Exception e) {
            log.error("预加载知识点 ID 集合失败", e);
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "外键预查失败，无法执行导入: " + e.getMessage());
        }
        return ids;
    }

    // ========================= 校验 =========================

    private ValidationResult validate(JsonNode node, String dataType, Set<String> validKpIds) {
        return switch (dataType) {
            case "topic" -> validateTopic(node);
            case "question" -> validateQuestion(node, validKpIds);
            case "dependency" -> validateDependency(node, validKpIds);
            default -> new ValidationResult(true, null, null);
        };
    }

    private ValidationResult validateTopic(JsonNode node) {
        String id = getTextOrNull(node, "id");
        if (id == null || id.isBlank()) {
            return new ValidationResult(false, "unknown", "id 为空");
        }
        String name = getTextOrNull(node, "name");
        if (name == null || name.isBlank()) {
            return new ValidationResult(false, id, "name 为空");
        }
        return new ValidationResult(true, id, null);
    }

    private ValidationResult validateQuestion(JsonNode node, Set<String> validKpIds) {
        String topicId = getTextOrNull(node, "topicId");
        if (topicId == null || topicId.isBlank()) {
            return new ValidationResult(false, getTextNodeId(node), "topicId 为空");
        }
        if (!validKpIds.contains(topicId)) {
            return new ValidationResult(false, getTextNodeId(node), "topicId=" + topicId + " 不存在");
        }
        String prompt = getTextOrNull(node, "prompt");
        if (prompt == null || prompt.isBlank()) {
            return new ValidationResult(false, getTextNodeId(node), "prompt 为空");
        }
        // self 类型（自评题）answer 为可选字段，其他类型必须非空
        String type = getTextOrNull(node, "type");
        if (!"self".equals(type)) {
            String answer = getTextOrNull(node, "answer");
            if (answer == null || answer.isBlank()) {
                return new ValidationResult(false, getTextNodeId(node), "answer 为空");
            }
        }
        String difficulty = getTextOrNull(node, "difficulty");
        if (!Set.of("basic", "standard", "challenge", "advanced").contains(difficulty)) {
            return new ValidationResult(false, getTextNodeId(node), "difficulty 非法: " + difficulty);
        }
        if (type != null && !Set.of("numeric", "choice", "short", "self").contains(type)) {
            return new ValidationResult(false, getTextNodeId(node), "type 非法: " + type);
        }
        return new ValidationResult(true, getTextNodeId(node), null);
    }

    private ValidationResult validateDependency(JsonNode node, Set<String> validKpIds) {
        String topicId = getTextOrNull(node, "topicId");
        String prereqId = getTextOrNull(node, "prerequisiteId");
        if (topicId == null || topicId.isBlank()) {
            return new ValidationResult(false, "unknown", "topicId 为空");
        }
        if (prereqId == null || prereqId.isBlank()) {
            return new ValidationResult(false, topicId, "prerequisiteId 为空");
        }
        if (!validKpIds.contains(topicId)) {
            return new ValidationResult(false, topicId, "topicId=" + topicId + " 不存在");
        }
        if (!validKpIds.contains(prereqId)) {
            return new ValidationResult(false, topicId, "prerequisiteId=" + prereqId + " 不存在");
        }
        String strength = getTextOrNull(node, "strength");
        if (!Set.of("hard", "soft").contains(strength)) {
            return new ValidationResult(false, topicId, "strength 非法: " + strength);
        }
        return new ValidationResult(true, topicId + "->" + prereqId, null);
    }

    private ValidationResult validateStandard(JsonNode node) {
        String id = getTextOrNull(node, "id");
        if (id == null || id.isBlank()) {
            return new ValidationResult(false, "unknown", "id 为空");
        }
        String text = getTextOrNull(node, "text");
        if (text == null || text.isBlank()) {
            return new ValidationResult(false, id, "text 为空");
        }
        String stage = deriveStage(id);
        if (stage == null) {
            return new ValidationResult(false, id, "无法推导 stage，id=" + id);
        }
        return new ValidationResult(true, id, null);
    }

    // ========================= 字段映射 & 插入 =========================

    private void insertRecord(JsonNode node, String dataType, Set<String> validKpIds) {
        switch (dataType) {
            case "topic" -> insertTopic(node);
            case "question" -> insertQuestion(node);
            case "dependency" -> insertDependency(node);
        }
    }

    private void insertTopic(JsonNode node) {
        KnowledgePoint kp = mapToKnowledgePoint(node);
        // 安全降级：如果学科仍为 null，从知识点 ID 前缀推导
        if (kp.getSubject() == null) {
            String fallback = deriveSubject(kp.getKnowledgePointId());
            if (fallback != null) {
                log.warn("知识点学科降级推导: id={} -> subject={} (from kp prefix)",
                        kp.getKnowledgePointId(), fallback);
                kp.setSubject(fallback);
            } else {
                log.error("知识点学科推导彻底失败! id={}, 将使用默认值 '未知'",
                        kp.getKnowledgePointId());
                kp.setSubject("未知");
            }
        }
        knowledgePointMapper.insert(kp);
        // 自动创建科目记录（如果不存在）
        if (kp.getSubject() != null) {
            ensureSubjectExists(kp.getSubject(), "default");
        }
    }

    /**
     * 确保科目记录存在。如果 subject 表中没有该科目，则自动创建。
     * Demo 阶段学段默认为 "小学"（知识点数据来源以小学为主）。
     */
    private void ensureSubjectExists(String subjectName, String tenantId) {
        try {
            Long count = subjectMapper.selectCount(
                new LambdaQueryWrapper<Subject>()
                    .eq(Subject::getName, subjectName)
                    .eq(Subject::getTenantId, tenantId)
            );
            if (count != null && count == 0) {
                Subject subject = new Subject();
                subject.setName(subjectName);
                subject.setStage("小学");
                subject.setTenantId(tenantId);
                subjectMapper.insert(subject);
                log.info("自动创建科目记录: name={}, stage={}", subjectName, "小学");
            }
        } catch (Exception e) {
            log.warn("自动创建科目记录失败(可能并发创建): {}", subjectName, e);
        }
    }

    private void insertQuestion(JsonNode node) {
        QuestionBank q = mapToQuestionBank(node);
        questionBankMapper.insert(q);
    }

    private void insertDependency(JsonNode node) {
        KnowledgePointDependency dep = mapToDependency(node);
        dependencyMapper.insert(dep);
    }

    // ---------- 映射方法 ----------

    private KnowledgePoint mapToKnowledgePoint(JsonNode node) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setKnowledgePointId(getTextOrNull(node, "id"));
        // 优先从 standards 推导学科，失败则从 topic 的 subject 字段获取
        String subject = deriveSubjectFromStandards(node.get("standards"));
        String subjectSource = "standards";
        if (subject == null) {
            subject = convertSubjectFromEnglish(getTextOrNull(node, "subject"));
            subjectSource = "topic.subject";
        }
        if (subject == null) {
            log.error("知识点学科推导失败! id={}, standards={}, topic.subject={}",
                    kp.getKnowledgePointId(),
                    node.get("standards"),
                    getTextOrNull(node, "subject"));
        }
        log.debug("知识点映射: id={}, subject={} (来源: {})", kp.getKnowledgePointId(), subject, subjectSource);
        kp.setSubject(subject);
        kp.setName(getTextOrNull(node, "name"));
        kp.setLevel(2); // Demo 阶段统一 level=2
        kp.setParentId(null); // Demo 阶段不推导层级
        kp.setStatus("pending_review");
        kp.setStandards(serializeJson(node.get("standards")));
        kp.setMasteryCriteria(serializeJson(node.get("mastery_criteria")));
        kp.setDiagnosticQuestion(serializeJson(node.get("diagnostic_question")));
        return kp;
    }

    /**
     * 题目映射
     * <p>
     * question_bank.content 为 TEXT（64KB），当前 prompt 较短无风险。
     */
    private QuestionBank mapToQuestionBank(JsonNode node) {
        QuestionBank q = new QuestionBank();
        q.setKnowledgePointId(getTextOrNull(node, "topicId"));
        q.setContent(getTextOrNull(node, "prompt"));
        q.setAnswer(getTextOrNull(node, "answer"));
        q.setAnalysis(getTextOrNull(node, "explanation"));
        q.setDifficulty(normalizeDifficulty(getTextOrNull(node, "difficulty")));
        q.setType(getTextOrNull(node, "type"));
        q.setChoices(serializeJson(node.get("choices")));
        return q;
    }

    private KnowledgePointDependency mapToDependency(JsonNode node) {
        KnowledgePointDependency dep = new KnowledgePointDependency();
        dep.setKnowledgePointId(getTextOrNull(node, "topicId"));
        dep.setPrerequisiteId(getTextOrNull(node, "prerequisiteId"));
        dep.setDependencyType(getTextOrNull(node, "strength"));
        return dep;
    }

    private CurriculumStandard mapToCurriculumStandard(JsonNode node) {
        CurriculumStandard cs = new CurriculumStandard();
        cs.setCode(getTextOrNull(node, "id"));
        cs.setSubject(deriveSubject(getTextOrNull(node, "id")));
        cs.setStage(deriveStage(getTextOrNull(node, "id")));
        cs.setDescription(getTextOrNull(node, "text"));
        return cs;
    }

    // ========================= 课程标准批量插入 =========================

    /**
     * 批量插入课程标准（每条记录独立事务），返回 [successCount, skipCount, failCount]
     * <p>
     * 事务策略：与其他三种类型保持一致，每条记录使用 TransactionTemplate 独立事务，
     * 单条失败不影响同批次其他记录。
     */
    private int[] batchInsertStandards(List<CurriculumStandard> batch, Long importId,
                                        List<DataImportErrorLog> errors, List<String> errorSamples) {
        int success = 0, skip = 0, fail = 0;
        for (CurriculumStandard cs : batch) {
            try {
                final CurriculumStandard current = cs;
                transactionTemplate.executeWithoutResult(status -> {
                    curriculumStandardMapper.insert(current);
                });
                success++;
            } catch (DuplicateKeyException e) {
                skip++;
                log.warn("课程标准 code 冲突，跳过: {}", cs.getCode());
            } catch (DataIntegrityViolationException e) {
                fail++;
                String recordId = cs.getCode() != null ? cs.getCode() : "unknown";
                String msg = "课程标准插入失败: " + recordId + " - 数据完整性约束违反: " + e.getMessage();
                log.error("课程标准数据完整性异常: code={}, subject={}, stage={}", cs.getCode(), cs.getSubject(), cs.getStage(), e);
                addError(errors, importId, recordId, msg);
                if (errorSamples.size() < 5) errorSamples.add(formatSample(recordId, msg));
            } catch (Exception e) {
                fail++;
                String recordId = cs.getCode() != null ? cs.getCode() : "unknown";
                String msg = "课程标准插入失败: " + recordId + " - " + e.getMessage();
                log.error("课程标准插入异常: code={}, subject={}, stage={}", cs.getCode(), cs.getSubject(), cs.getStage(), e);
                addError(errors, importId, recordId, msg);
                if (errorSamples.size() < 5) errorSamples.add(formatSample(recordId, msg));
            }
        }
        return new int[]{success, skip, fail};
    }

    // ========================= stage 推导 =========================

    /**
     * 从 id 前缀推导学段
     * <p>
     * ID 格式分两类：
     * 1. 含学段编号："{prefix}:{stage}.*"，stage 数字决定学段：
     *    - 1~3 → "小学"（义务教育第一至第三学段，1-6 年级）
     *    - 4   → "初中"（义务教育第四学段，7-9 年级）
     *    - 5   → "高中"
     * 2. 不含学段编号（字母前缀）：如 chs-2022:CA.01（历史）、cks-2022:SQ.01（化学）等，
     *    这些学科仅在初中开设，根据学科前缀推断默认学段为 "初中"。
     * <p>
     * 高中特殊标识：id 中含 "H."（如 cms-2017:H.GA.04）或 "hs-"（如 cps-hs-2017:B1.1.1）
     */
    private String deriveStage(String id) {
        if (id == null) return null;
        // 高中：id 中含 "H."（如 cms-2017:H.GA.04）或 "hs-"（如 cps-hs-2017:B1.1.1）
        if (id.contains(":H.") || id.contains("hs-")) {
            return "高中";
        }
        // 提取冒号后的部分
        int colonIdx = id.indexOf(':');
        if (colonIdx >= 0 && colonIdx + 1 < id.length()) {
            char stageChar = id.charAt(colonIdx + 1);
            // 义务教育：学段编号 1~5
            if (stageChar >= '1' && stageChar <= '3') {
                return "小学";
            }
            if (stageChar == '4') {
                return "初中";
            }
            if (stageChar == '5') {
                return "高中";
            }
        }
        // 无学段编号的学科：根据学科前缀推断默认学段
        // 历史(chs)、化学(cks)、生物(cbs)、地理(cgs) 仅在初中开设
        String prefix = extractSubjectPrefix(id);
        if (Set.of("chs", "cks", "cbs", "cgs").contains(prefix)) {
            return "初中";
        }
        return null;
    }

    /**
     * 从 ID 中提取学科前缀（去掉版本号和冒号后的部分）
     * 如 "chs-2022:CA.01" → "chs"，"cps-hs-2017:B1.1.1" → "cps"
     */
    private String extractSubjectPrefix(String id) {
        if (id == null) return null;
        int colonIdx = id.indexOf(':');
        String beforeColon = colonIdx > 0 ? id.substring(0, colonIdx) : id;
        int dashIdx = beforeColon.lastIndexOf('-');
        if (dashIdx > 0) {
            beforeColon = beforeColon.substring(0, dashIdx);
        }
        if (beforeColon.endsWith("-hs")) {
            beforeColon = beforeColon.substring(0, beforeColon.length() - 3);
        }
        return beforeColon;
    }

    // ========================= subject 推导 =========================

    /**
     * 从课程标准 ID 前缀推导学科
     * <p>
     * 前缀映射（根据 cn-math-taxonomy 最新数据）：
     * 课程标准 ID 格式：{prefix}-{year}:{stage}.{domain}.{number}
     * <p>
     * 课标前缀 → 学科：
     * - cms- → 数学（cms-2022 义务教育、cms-2017 高中）
     * - ces- → 英语（ces-2022 义务教育、ces-2017 高中）
     * - cps- → 物理（cps-2022 义务教育、cps-hs-2017 高中）
     * - ccs- → 语文（ccs-2022 义务教育）
     * - chs- → 历史（chs-2022 义务教育）
     * - cks- → 化学（cks-2022 义务教育）
     * - cbs- → 生物（cbs-2022 义务教育）
     * - css- → 科学（css-2022 义务教育）
     * - cds- → 道德与法治（cds-2022 义务教育）
     * - cgs- → 地理（cgs-2022 义务教育）
     * <p>
     * 知识点 ID 前缀 → 学科（用于 standards JSON 中的引用）：
     * - cnm- → 数学, cne- → 英语, cnp- → 物理
     * - cnc- → 语文, cnh- → 历史, cnk- → 化学
     * - cnb- → 生物, cns- → 科学, cnd- → 道德与法治, cng- → 地理
     * <p>
     * 注意：高中物理 ID 格式为 cps-hs-2017:X.Y.Z，去掉版本号后为 cps-hs，
     * 需要额外去掉 -hs 后缀才能匹配到 cps → 物理。
     */
    private String deriveSubject(String standardId) {
        String prefix = extractSubjectPrefix(standardId);
        if (prefix == null) return null;
        return switch (prefix) {
            // 课程标准前缀
            case "cms" -> "数学";
            case "ces" -> "英语";
            case "cps" -> "物理";
            case "ccs" -> "语文";
            case "chs" -> "历史";
            case "cks" -> "化学";
            case "cbs" -> "生物";
            case "css" -> "科学";
            case "cds" -> "道德与法治";
            case "cgs" -> "地理";
            // 知识点 ID 前缀（standards JSON 中可能引用）
            case "cnm" -> "数学";
            case "cne" -> "英语";
            case "cnp" -> "物理";
            case "cnc" -> "语文";
            case "cnh" -> "历史";
            case "cnk" -> "化学";
            case "cnb" -> "生物";
            case "cns" -> "科学";
            case "cnd" -> "道德与法治";
            case "cng" -> "地理";
            default -> null;
        };
    }

    /**
     * 从知识点的 standards JSON 推导学科
     * standards 格式：[{"id": "cms-2022:1.NA.01", "confidence": "exact"}]
     * 取第一个标准的学科
     */
    private String deriveSubjectFromStandards(JsonNode standardsNode) {
        if (standardsNode == null || !standardsNode.isArray() || standardsNode.isEmpty()) {
            return null;
        }
        JsonNode first = standardsNode.get(0);
        if (first == null) return null;
        String id = getTextOrNull(first, "id");
        return deriveSubject(id);
    }

    /**
     * 将英文学科名转换为中文
     * 数据源中的 subject 字段为英文（如 "chinese", "math"），需要转换为中文
     */
    private String convertSubjectFromEnglish(String subject) {
        if (subject == null || subject.isBlank()) return null;
        return switch (subject.toLowerCase()) {
            case "chinese" -> "语文";
            case "math", "mathematics" -> "数学";
            case "english" -> "英语";
            case "physics" -> "物理";
            case "chemistry" -> "化学";
            case "biology" -> "生物";
            case "history" -> "历史";
            case "geography" -> "地理";
            case "science" -> "科学";
            case "morality", "moral_education", "civics" -> "道德与法治";
            case "politics" -> "道德与法治"; // 旧数据可能使用 politics
            default -> subject; // 如果已经是中文或其他值，直接返回
        };
    }

    // ========================= 查询方法 =========================

    @Override
    public PageResult<ImportLogVO> getImportLogs(String dataType, int page, int size) {
        Page<DataImportLog> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<DataImportLog> wrapper = new LambdaQueryWrapper<>();
        if (dataType != null && !dataType.isBlank()) {
            wrapper.eq(DataImportLog::getDataType, dataType);
        }
        wrapper.orderByDesc(DataImportLog::getImportedAt);

        Page<DataImportLog> result = importLogMapper.selectPage(pageParam, wrapper);

        List<ImportLogVO> voList = result.getRecords().stream()
                .map(this::mapToImportLogVO)
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), page, size, voList);
    }

    @Override
    public PageResult<DataImportErrorLog> getImportErrors(Long importId, int page, int size) {
        Page<DataImportErrorLog> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<DataImportErrorLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataImportErrorLog::getImportId, importId);
        wrapper.orderByDesc(DataImportErrorLog::getCreateTime);

        Page<DataImportErrorLog> result = errorLogMapper.selectPage(pageParam, wrapper);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    // ========================= 辅助方法 =========================

    private ImportLogVO mapToImportLogVO(DataImportLog log) {
        return ImportLogVO.builder()
                .id(log.getImportId())
                .dataType(log.getDataType())
                .fileSource(log.getFileSource())
                .successCount(log.getSuccessCount())
                .failCount(log.getFailCount())
                .skipCount(log.getSkipCount())
                .importedAt(log.getImportedAt())
                .build();
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) return null;
        return val.asText();
    }

    private String getNodeId(JsonNode node, String dataType) {
        return switch (dataType) {
            case "topic" -> getTextOrNull(node, "id");
            case "question" -> getTextOrNull(node, "id");
            case "dependency" -> getTextOrNull(node, "topicId") + "->" + getTextOrNull(node, "prerequisiteId");
            case "standard" -> getTextOrNull(node, "id");
            default -> "unknown";
        };
    }

    private String getTextNodeId(JsonNode node) {
        String id = getTextOrNull(node, "id");
        return id != null ? id : "unknown";
    }

    private String serializeJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 提取异常的根本原因信息（遍历 cause 链，取最深层非空 message）
     */
    private String extractRootCause(Throwable e) {
        Throwable cause = e;
        String msg = e.getMessage();
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
            if (cause.getMessage() != null) {
                msg = cause.getMessage();
            }
        }
        if (msg == null) {
            msg = e.getClass().getSimpleName();
        }
        // 截断到 450 字符，留空间给前缀
        return msg.length() > 450 ? msg.substring(0, 450) + "..." : msg;
    }

    private void addError(List<DataImportErrorLog> errors, Long importId, String recordId, String reason) {
        DataImportErrorLog error = new DataImportErrorLog();
        error.setImportId(importId);
        error.setRecordId(recordId != null ? truncate(recordId, 100) : null);
        error.setErrorReason(reason != null ? truncate(reason, 500) : "未知错误");
        errors.add(error);
    }

    private String formatSample(String recordId, String reason) {
        return String.format("[%s] %s", recordId != null ? recordId : "unknown", reason != null ? reason : "未知错误");
    }

    /**
     * 难度值归一化：数据源中的 "advanced" 映射为产品定义的 "challenge"
     */
    private String normalizeDifficulty(String difficulty) {
        if ("advanced".equals(difficulty)) {
            return "challenge";
        }
        return difficulty;
    }

    // ========================= 校验结果内部类 =========================

    private static class ValidationResult {
        final boolean valid;
        final String recordId;
        final String message;

        ValidationResult(boolean valid, String recordId, String message) {
            this.valid = valid;
            this.recordId = recordId;
            this.message = message;
        }

        @Override
        public String toString() {
            return "ValidationResult{valid=" + valid + ", recordId='" + recordId + "', message='" + message + "'}";
        }
    }
}
