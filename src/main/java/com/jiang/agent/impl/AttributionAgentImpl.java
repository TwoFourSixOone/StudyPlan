package com.jiang.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.agent.AttributionAgent;
import com.jiang.client.AgentScopeClient;
import com.jiang.common.exception.AttributionException;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.common.util.AgentOutputValidator;
import com.jiang.dto.ExamQuestionAttributionDTO;
import com.jiang.dto.ExamQuestionOcrDTO;
import com.jiang.mapper.KnowledgePointMapper;
import com.jiang.mapper.SubjectMapper;
import com.jiang.model.entity.KnowledgePoint;
import com.jiang.model.entity.Subject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 归因 Agent 实现
 * 使用 AgentScope SDK 调用 LLM，将 OCR 识别的题目映射到知识点
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@Component
@Slf4j
public class AttributionAgentImpl implements AttributionAgent {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AgentScopeClient agentScopeClient;

    @Autowired
    private AgentOutputValidator validator;

    @Autowired
    private SubjectMapper subjectMapper;

    @Autowired
    private KnowledgePointMapper knowledgePointMapper;

    @Override
    public List<ExamQuestionAttributionDTO> attribute(List<ExamQuestionOcrDTO> ocrResults, Long subjectId, String tenantId) {
        log.info("归因 Agent 开始处理: subjectId={}, questionCount={}, tenantId={}", subjectId, ocrResults.size(), tenantId);

        // 1. 查询科目名称
        String subjectName = getSubjectName(subjectId);

        // 2. 查询该科目下的活跃知识点
        List<KnowledgePoint> knowledgePoints = knowledgePointMapper.selectList(
                new LambdaQueryWrapper<KnowledgePoint>()
                        .eq(KnowledgePoint::getSubject, subjectName)
                        .eq(KnowledgePoint::getTenantId, tenantId)
                        .eq(KnowledgePoint::getStatus, "active"));

        if (knowledgePoints.isEmpty()) {
            log.warn("科目 {} 下无活跃知识点，全部标记为待人工归因", subjectName);
            return buildFallbackResults(ocrResults);
        }

        // 3. 构造 Prompt
        String prompt = buildAttributionPrompt(ocrResults, subjectId, knowledgePoints);

        // 4. 调用 AgentScope LLM
        String agentOutput;
        try {
            agentOutput = agentScopeClient.call(prompt);
        } catch (BusinessException e) {
            log.error("归因 Agent LLM 调用失败: {}", e.getMessage(), e);
            throw new AttributionException("归因 Agent 调用失败: " + e.getMessage(), e);
        }

        // 5. 校验输出
        AgentOutputValidator.ValidationResult validationResult = validator.validateAttribution(agentOutput);
        if (!validationResult.isSuccess()) {
            log.warn("归因 Agent 输出校验失败: {}，降级为待人工归因", validationResult.getMessage());
            return buildFallbackResults(ocrResults);
        }

        // 6. 解析结果
        return parseAttributionResult(agentOutput, ocrResults);
    }

    private String getSubjectName(Long subjectId) {
        Subject subject = subjectMapper.selectById(subjectId);
        if (subject == null) {
            throw new AttributionException("科目不存在: subjectId=" + subjectId);
        }
        return subject.getName();
    }

    private String buildAttributionPrompt(List<ExamQuestionOcrDTO> ocrResults, Long subjectId,
                                           List<KnowledgePoint> knowledgePoints) {
        // 构造知识点列表文本
        String kpText = knowledgePoints.stream()
                .map(kp -> String.format("- ID: %s, 名称: %s", kp.getKnowledgePointId(), kp.getName()))
                .collect(Collectors.joining("\n"));

        // 构造题目文本
        String questionText = ocrResults.stream()
                .map(ocr -> String.format("题目 %d:\n%s", ocr.getQuestionIndex(), ocr.getQuestionContent()))
                .collect(Collectors.joining("\n\n"));

        String promptTemplate = loadPromptTemplate();
        return promptTemplate
                .replace("{subjectId}", String.valueOf(subjectId))
                .replace("{knowledgePoints}", kpText)
                .replace("{questions}", questionText);
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/versions/attribution_v1.txt");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes());
            }
        } catch (IOException e) {
            log.error("加载归因 Agent Prompt 模板失败: {}", e.getMessage());
            throw new BusinessException(ErrorCodes.AGENT_CALL_FAILED, "归因 Agent Prompt 模板加载失败");
        }
    }

    private List<ExamQuestionAttributionDTO> parseAttributionResult(String agentOutput,
                                                                      List<ExamQuestionOcrDTO> ocrResults) {
        try {
            JsonNode jsonArray = OBJECT_MAPPER.readTree(agentOutput);
            Map<Integer, ExamQuestionOcrDTO> ocrMap = ocrResults.stream()
                    .collect(Collectors.toMap(
                            dto -> dto.getQuestionIndex() != null ? dto.getQuestionIndex() : 0,
                            dto -> dto,
                            (a, b) -> a));

            List<ExamQuestionAttributionDTO> results = new ArrayList<>();
            for (JsonNode item : jsonArray) {
                int questionIndex = item.get("questionIndex").asInt();
                String kpId = item.get("knowledgePointId").asText("");
                String confidence = item.has("confidence") ? item.get("confidence").asText("low") : "low";

                ExamQuestionOcrDTO ocr = ocrMap.get(questionIndex);
                ExamQuestionAttributionDTO dto = new ExamQuestionAttributionDTO();
                dto.setQuestionIndex(questionIndex);
                dto.setQuestionContent(ocr != null ? ocr.getQuestionContent() : "");
                dto.setKnowledgePointId(kpId.isEmpty() ? null : kpId);
                dto.setAttributionStatus(kpId.isEmpty() ? "pending_manual_review" :
                        ("low".equals(confidence) ? "pending_manual_review" : "completed"));
                dto.setQuestionType(ocr != null ? ocr.getQuestionType() : null);
                dto.setDifficulty(ocr != null ? ocr.getDifficulty() : null);
                results.add(dto);
            }

            log.info("归因 Agent 解析完成: total={}, attributed={}, pending={}",
                    results.size(),
                    results.stream().filter(r -> "completed".equals(r.getAttributionStatus())).count(),
                    results.stream().filter(r -> "pending_manual_review".equals(r.getAttributionStatus())).count());

            return results;
        } catch (Exception e) {
            log.error("归因 Agent 输出解析失败: {}", e.getMessage(), e);
            return buildFallbackResults(ocrResults);
        }
    }

    /**
     * 降级处理：所有题目标记为待人工归因
     */
    private List<ExamQuestionAttributionDTO> buildFallbackResults(List<ExamQuestionOcrDTO> ocrResults) {
        return ocrResults.stream()
                .map(ocr -> {
                    ExamQuestionAttributionDTO dto = new ExamQuestionAttributionDTO();
                    dto.setQuestionIndex(ocr.getQuestionIndex());
                    dto.setQuestionContent(ocr.getQuestionContent());
                    dto.setKnowledgePointId(null);
                    dto.setAttributionStatus("pending_manual_review");
                    dto.setQuestionType(ocr.getQuestionType());
                    dto.setDifficulty(ocr.getDifficulty());
                    return dto;
                })
                .collect(Collectors.toList());
    }
}
