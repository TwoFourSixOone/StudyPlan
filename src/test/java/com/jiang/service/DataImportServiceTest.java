package com.jiang.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.mapper.*;
import com.jiang.model.entity.*;
import com.jiang.service.impl.DataImportServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DataImportService 单元测试
 * <p>
 * 测试 Service 核心逻辑：参数校验、字段映射、stage 推导、枚举校验。
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@ExtendWith(MockitoExtension.class)
class DataImportServiceTest {

    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    @Mock
    private QuestionBankMapper questionBankMapper;

    @Mock
    private KnowledgePointDependencyMapper dependencyMapper;

    @Mock
    private CurriculumStandardMapper curriculumStandardMapper;

    @Mock
    private DataImportLogMapper importLogMapper;

    @Mock
    private DataImportErrorLogMapper errorLogMapper;

    @InjectMocks
    private DataImportServiceImpl dataImportService;

    // ========================= 参数校验 =========================

    @Test
    void should_throwException_when_dataTypeInvalid() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> dataImportService.importData("invalid_type"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("不支持的数据类型"));
    }

    @Test
    void should_throwException_when_dataTypeNull() {
        assertThrows(BusinessException.class,
                () -> dataImportService.importData(null));
    }

    // ========================= 字段映射验证（通过 public importData 间接验证） =========================

    @Test
    void should_rejectUnknownDataType() {
        // 验证不支持的 dataType 抛出 BusinessException
        assertThrows(BusinessException.class, () -> dataImportService.importData("unknown_type"));
    }

    // ========================= 枚举校验（通过反射测试私有方法） =========================

    @Test
    void should_validateQuestionTypeEnum_includingShortAndSelf() throws Exception {
        // 验证 type 枚举包含 short/self（O4 修正）
        var method = DataImportServiceImpl.class.getDeclaredMethod("validateQuestion",
                com.fasterxml.jackson.databind.JsonNode.class, java.util.Set.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var validKpIds = java.util.Set.of("kp001");

        // type=short 应通过
        var shortNode = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"answer\":\"y\",\"difficulty\":\"basic\",\"type\":\"short\"}");
        Object result = method.invoke(dataImportService, shortNode, validKpIds);
        assertNotNull(result);
        assertTrue(result.toString().contains("valid=true"), "type=short 应通过校验");

        // type=self 无 answer 应通过（self 类型 answer 为可选字段）
        var selfNode = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"difficulty\":\"basic\",\"type\":\"self\"}");
        Object result2 = method.invoke(dataImportService, selfNode, validKpIds);
        assertNotNull(result2);
        assertTrue(result2.toString().contains("valid=true"), "type=self 无 answer 应通过校验");

        // type=self 有 answer 也应通过
        var selfWithAnswerNode = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"answer\":\"y\",\"difficulty\":\"basic\",\"type\":\"self\"}");
        Object result2b = method.invoke(dataImportService, selfWithAnswerNode, validKpIds);
        assertTrue(result2b.toString().contains("valid=true"), "type=self 有 answer 应通过校验");

        // type=numeric 无 answer 应失败
        var numericNoAnswerNode = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"difficulty\":\"basic\",\"type\":\"numeric\"}");
        Object result2c = method.invoke(dataImportService, numericNoAnswerNode, validKpIds);
        assertTrue(result2c.toString().contains("valid=false"), "type=numeric 无 answer 应失败");

        // type=invalid 应失败
        var invalidNode = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"answer\":\"y\",\"difficulty\":\"basic\",\"type\":\"essay\"}");
        Object result3 = method.invoke(dataImportService, invalidNode, validKpIds);
        assertNotNull(result3);
        assertTrue(result3.toString().contains("valid=false"));
    }

    @Test
    void should_validateDifficultyEnum_basicStandardChallenge() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("validateQuestion",
                com.fasterxml.jackson.databind.JsonNode.class, java.util.Set.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var validKpIds = java.util.Set.of("kp001");

        // difficulty=basic/standard/challenge 均应通过
        for (String diff : List.of("basic", "standard", "challenge")) {
            var node = realMapper.readTree(String.format(
                    "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"answer\":\"y\",\"difficulty\":\"%s\",\"type\":\"numeric\"}", diff));
            Object result = method.invoke(dataImportService, node, validKpIds);
            assertTrue(result.toString().contains("valid=true"),
                    "difficulty=" + diff + " 应通过校验");
        }

        // difficulty=easy（旧枚举值）应失败
        var easyNode = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"answer\":\"y\",\"difficulty\":\"easy\",\"type\":\"numeric\"}");
        Object result = method.invoke(dataImportService, easyNode, validKpIds);
        assertTrue(result.toString().contains("valid=false"),
                "difficulty=easy 应失败（不在枚举范围）");

        // difficulty=advanced（数据源术语）应通过校验
        var advancedNode = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prompt\":\"x\",\"answer\":\"y\",\"difficulty\":\"advanced\",\"type\":\"numeric\"}");
        Object resultAdvanced = method.invoke(dataImportService, advancedNode, validKpIds);
        assertTrue(resultAdvanced.toString().contains("valid=true"),
                "difficulty=advanced 应通过校验（运行时归一化为 challenge）");
    }

    @Test
    void should_normalizeDifficulty_advancedToChallenge() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("normalizeDifficulty", String.class);
        method.setAccessible(true);

        // advanced → challenge
        assertEquals("challenge", method.invoke(dataImportService, "advanced"));
        // 其他值保持不变
        assertEquals("basic", method.invoke(dataImportService, "basic"));
        assertEquals("standard", method.invoke(dataImportService, "standard"));
        assertEquals("challenge", method.invoke(dataImportService, "challenge"));
        // null 安全
        assertNull(method.invoke(dataImportService, (String) null));
    }

    @Test
    void should_validateDependencyStrength_hardAndSoft() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("validateDependency",
                com.fasterxml.jackson.databind.JsonNode.class, java.util.Set.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var validKpIds = java.util.Set.of("kp001", "kp002");

        // strength=hard 应通过
        var hardNode = realMapper.readTree(
                "{\"topicId\":\"kp002\",\"prerequisiteId\":\"kp001\",\"strength\":\"hard\"}");
        Object result = method.invoke(dataImportService, hardNode, validKpIds);
        assertTrue(result.toString().contains("valid=true"));

        // strength=soft 应通过
        var softNode = realMapper.readTree(
                "{\"topicId\":\"kp002\",\"prerequisiteId\":\"kp001\",\"strength\":\"soft\"}");
        Object result2 = method.invoke(dataImportService, softNode, validKpIds);
        assertTrue(result2.toString().contains("valid=true"));

        // strength=medium 应失败
        var medNode = realMapper.readTree(
                "{\"topicId\":\"kp002\",\"prerequisiteId\":\"kp001\",\"strength\":\"medium\"}");
        Object result3 = method.invoke(dataImportService, medNode, validKpIds);
        assertTrue(result3.toString().contains("valid=false"));
    }

    // ========================= stage 推导验证（O3 修正） =========================

    @Test
    void should_deriveStage_correctly() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("deriveStage", String.class);
        method.setAccessible(true);

        // === cms-2022（数学 2022 版）===
        // cms-2022:1~3 → 小学
        assertEquals("小学", method.invoke(dataImportService, "cms-2022:1.NA.01"));
        assertEquals("小学", method.invoke(dataImportService, "cms-2022:2.GG.03"));
        assertEquals("小学", method.invoke(dataImportService, "cms-2022:3.SP.01"));
        // cms-2022:4 → 初中
        assertEquals("初中", method.invoke(dataImportService, "cms-2022:4.NA.01"));
        // cms-2017:H → 高中
        assertEquals("高中", method.invoke(dataImportService, "cms-2017:H.FUN.01"));

        // === ces-2022（英语 2022 版）===
        assertEquals("小学", method.invoke(dataImportService, "ces-2022:1.PH.01"));
        assertEquals("小学", method.invoke(dataImportService, "ces-2022:2.PH.01"));
        assertEquals("小学", method.invoke(dataImportService, "ces-2022:3.PH.01"));
        assertEquals("初中", method.invoke(dataImportService, "ces-2022:4.PH.01"));

        // === ces-2017（高中英语）===
        assertEquals("高中", method.invoke(dataImportService, "ces-2017:H.B.PH.01"));
        assertEquals("高中", method.invoke(dataImportService, "ces-2017:H.S.PH.01"));

        // === cps-2022（物理 2022 版）===
        assertEquals("小学", method.invoke(dataImportService, "cps-2022:1.1.1"));
        assertEquals("小学", method.invoke(dataImportService, "cps-2022:3.2.1"));
        assertEquals("初中", method.invoke(dataImportService, "cps-2022:4.1.1"));
        // cps-2022:5 → 高中
        assertEquals("高中", method.invoke(dataImportService, "cps-2022:5.1.1"));

        // === cps-hs-2017（高中物理）===
        assertEquals("高中", method.invoke(dataImportService, "cps-hs-2017:B1.1.1"));
        assertEquals("高中", method.invoke(dataImportService, "cps-hs-2017:B2.3.1"));

        // === 未知前缀 → null ===
        assertNull(method.invoke(dataImportService, "unknown-prefix"));
        assertNull(method.invoke(dataImportService, (String) null));
    }

    // ========================= 字段映射方法验证 =========================

    @Test
    void should_mapTopicFields_correctly() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("mapToKnowledgePoint",
                com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var node = realMapper.readTree("""
                {
                    "id": "cnm_p001",
                    "name": "1到5各数的认识",
                    "mastery_criteria": ["能数数"],
                    "diagnostic_question": {"question": "3后面是?", "answer": "4"},
                    "standards": [{"id": "cms-2022:1.NA.01"}]
                }
                """);

        KnowledgePoint kp = (KnowledgePoint) method.invoke(dataImportService, node);
        assertEquals("cnm_p001", kp.getKnowledgePointId());
        assertEquals("数学", kp.getSubject());
        assertEquals("1到5各数的认识", kp.getName());
        assertEquals(2, kp.getLevel());
        assertNull(kp.getParentId());
        assertEquals("pending_review", kp.getStatus());
        // JSON 字段应序列化为字符串
        assertNotNull(kp.getMasteryCriteria());
        assertTrue(kp.getMasteryCriteria().contains("能数数"));
        assertNotNull(kp.getDiagnosticQuestion());
        assertNotNull(kp.getStandards());
    }

    @Test
    void should_mapQuestionFields_promptToContent() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("mapToQuestionBank",
                com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var node = realMapper.readTree("""
                {
                    "topicId": "cnm_p001",
                    "prompt": "3+2=?",
                    "answer": "5",
                    "explanation": "3加2等于5",
                    "difficulty": "basic",
                    "type": "numeric",
                    "choices": null
                }
                """);

        QuestionBank q = (QuestionBank) method.invoke(dataImportService, node);
        assertEquals("cnm_p001", q.getKnowledgePointId());
        assertEquals("3+2=?", q.getContent());       // prompt → content
        assertEquals("5", q.getAnswer());
        assertEquals("3加2等于5", q.getAnalysis());   // explanation → analysis
        assertEquals("basic", q.getDifficulty());
        assertEquals("numeric", q.getType());
        assertNull(q.getChoices());
    }

    @Test
    void should_mapDependencyFields_strengthToDependencyType() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("mapToDependency",
                com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var node = realMapper.readTree("""
                {
                    "topicId": "cnm_p002",
                    "prerequisiteId": "cnm_p001",
                    "strength": "hard",
                    "reason": "先修原因"
                }
                """);

        KnowledgePointDependency dep = (KnowledgePointDependency) method.invoke(dataImportService, node);
        assertEquals("cnm_p002", dep.getKnowledgePointId());   // topicId → knowledge_point_id
        assertEquals("cnm_p001", dep.getPrerequisiteId());
        assertEquals("hard", dep.getDependencyType());          // strength → dependency_type
    }

    @Test
    void should_mapStandardFields_idToCode() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("mapToCurriculumStandard",
                com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();

        // 小学课标
        var node1 = realMapper.readTree("""
                {"id": "cms-2022:1.NA.01", "text": "在实际情境中感悟并理解万以内数的意义"}
                """);
        CurriculumStandard cs1 = (CurriculumStandard) method.invoke(dataImportService, node1);
        assertEquals("cms-2022:1.NA.01", cs1.getCode());       // id → code
        assertEquals("数学", cs1.getSubject());
        assertEquals("小学", cs1.getStage());                   // 1 学段 → 小学
        assertEquals("在实际情境中感悟并理解万以内数的意义", cs1.getDescription()); // text → description

        // 初中课标
        var node2 = realMapper.readTree("""
                {"id": "cms-2022:4.NA.01", "text": "初中代数"}
                """);
        CurriculumStandard cs2 = (CurriculumStandard) method.invoke(dataImportService, node2);
        assertEquals("初中", cs2.getStage());

        // 高中课标
        var node3 = realMapper.readTree("""
                {"id": "cms-2017:H.FUN.01", "text": "高中函数"}
                """);
        CurriculumStandard cs3 = (CurriculumStandard) method.invoke(dataImportService, node3);
        assertEquals("高中", cs3.getStage());
    }

    // ========================= 外键预查验证 =========================

    @Test
    void should_rejectQuestion_when_topicIdNotInPreloadedSet() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("validateQuestion",
                com.fasterxml.jackson.databind.JsonNode.class, java.util.Set.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var validKpIds = java.util.Set.of("kp001");

        var node = realMapper.readTree(
                "{\"topicId\":\"kp999\",\"prompt\":\"x\",\"answer\":\"y\",\"difficulty\":\"basic\",\"type\":\"numeric\"}");
        Object result = method.invoke(dataImportService, node, validKpIds);
        assertTrue(result.toString().contains("valid=false"));
        assertTrue(result.toString().contains("kp999"));
    }

    @Test
    void should_rejectDependency_when_prerequisiteIdNotExists() throws Exception {
        var method = DataImportServiceImpl.class.getDeclaredMethod("validateDependency",
                com.fasterxml.jackson.databind.JsonNode.class, java.util.Set.class);
        method.setAccessible(true);

        var realMapper = new ObjectMapper();
        var validKpIds = java.util.Set.of("kp001");

        var node = realMapper.readTree(
                "{\"topicId\":\"kp001\",\"prerequisiteId\":\"kp999\",\"strength\":\"hard\"}");
        Object result = method.invoke(dataImportService, node, validKpIds);
        assertTrue(result.toString().contains("valid=false"));
        assertTrue(result.toString().contains("kp999"));
    }
}
