package com.jiang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.exception.BusinessException;
import com.jiang.common.exception.ErrorCodes;
import com.jiang.dto.ImportResultDTO;
import com.jiang.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DataImportController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private com.jiang.service.DataImportService dataImportService;

    /**
     * 配置 JwtUtil mock 通用行为（模拟鉴权通过 + 不刷新 token）
     */
    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");

        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    // ========================= 导入接口 =========================

    @Test
    void should_returnImportResult_when_importSuccess() throws Exception {
        stubJwtAuth();

        ImportResultDTO result = ImportResultDTO.builder()
                .importId(1L)
                .dataType("topic")
                .fileSource("topics.json")
                .successCount(100)
                .failCount(2)
                .skipCount(3)
                .errorSamples(List.of("[kp001] ID 冲突，已跳过", "[kp002] name 为空"))
                .importedAt(LocalDateTime.now())
                .build();
        when(dataImportService.importData(any(), eq("topic"))).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "topics.json",
                MediaType.APPLICATION_JSON_VALUE,
                "[{\"id\":\"kp001\",\"name\":\"测试知识点\"}]".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/import")
                        .file(file)
                        .param("dataType", "topic")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.importId").value(1))
                .andExpect(jsonPath("$.data.successCount").value(100))
                .andExpect(jsonPath("$.data.failCount").value(2))
                .andExpect(jsonPath("$.data.skipCount").value(3))
                .andExpect(jsonPath("$.data.errorSamples").isArray())
                .andExpect(jsonPath("$.data.errorSamples[0]").value("[kp001] ID 冲突，已跳过"));
    }

    @Test
    void should_return500_when_fileMissing() throws Exception {
        stubJwtAuth();

        mockMvc.perform(multipart("/api/admin/import")
                        .header("Authorization", "Bearer valid_token")
                        .param("dataType", "topic"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void should_return401_when_noJwt() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "topics.json",
                MediaType.APPLICATION_JSON_VALUE,
                "[{\"id\":\"kp001\",\"name\":\"测试知识点\"}]".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/import")
                        .file(file)
                        .param("dataType", "topic"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_returnErrorSamples_when_importHasErrors() throws Exception {
        stubJwtAuth();

        ImportResultDTO result = ImportResultDTO.builder()
                .importId(2L)
                .dataType("question")
                .fileSource("questions.json")
                .successCount(50)
                .failCount(10)
                .skipCount(5)
                .errorSamples(List.of(
                        "[q001] topicId=not_exist 不存在",
                        "[q002] prompt 为空",
                        "[q003] difficulty 非法: easy"
                ))
                .importedAt(LocalDateTime.now())
                .build();
        when(dataImportService.importData(any(), eq("question"))).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "questions.json",
                MediaType.APPLICATION_JSON_VALUE,
                "[{\"topicId\":\"q001\",\"prompt\":\"测试\"}]".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/import")
                        .file(file)
                        .param("dataType", "question")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorSamples").isArray())
                .andExpect(jsonPath("$.data.errorSamples.length()").value(3))
                .andExpect(jsonPath("$.data.errorSamples[0]").value("[q001] topicId=not_exist 不存在"));
    }

    // ========================= 日志查询接口 =========================

    @Test
    void should_returnImportLogs_when_queryLogs() throws Exception {
        stubJwtAuth();

        com.jiang.common.result.PageResult<com.jiang.vo.ImportLogVO> pageResult =
                com.jiang.common.result.PageResult.of(2L, 1, 20, List.of(
                        com.jiang.vo.ImportLogVO.builder()
                                .id(1L).dataType("topic").fileSource("json/topics.json")
                                .successCount(100).failCount(0).skipCount(0)
                                .importedAt(LocalDateTime.now())
                                .build(),
                        com.jiang.vo.ImportLogVO.builder()
                                .id(2L).dataType("question").fileSource("json/questions.json")
                                .successCount(50).failCount(2).skipCount(1)
                                .importedAt(LocalDateTime.now())
                                .build()
                ));
        when(dataImportService.getImportLogs(isNull(), eq(1), eq(20))).thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/import/logs")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].dataType").value("topic"))
                .andExpect(jsonPath("$.data.records[1].dataType").value("question"));
    }

    @Test
    void should_filterLogs_when_dataTypeProvided() throws Exception {
        stubJwtAuth();

        com.jiang.common.result.PageResult<com.jiang.vo.ImportLogVO> pageResult =
                com.jiang.common.result.PageResult.of(1L, 1, 20, List.of(
                        com.jiang.vo.ImportLogVO.builder()
                                .id(1L).dataType("topic").fileSource("json/topics.json")
                                .successCount(100).failCount(0).skipCount(0)
                                .importedAt(LocalDateTime.now())
                                .build()
                ));
        when(dataImportService.getImportLogs(eq("topic"), eq(1), eq(20))).thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/import/logs")
                        .header("Authorization", "Bearer valid_token")
                        .param("dataType", "topic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].dataType").value("topic"));
    }

    // ========================= 错误记录查询接口 =========================

    @Test
    void should_returnErrorLogs_when_queryErrors() throws Exception {
        stubJwtAuth();

        com.jiang.model.entity.DataImportErrorLog error = new com.jiang.model.entity.DataImportErrorLog();
        error.setErrorId(1L);
        error.setImportId(1L);
        error.setRecordId("kp001");
        error.setErrorReason("ID 冲突");

        com.jiang.common.result.PageResult<com.jiang.model.entity.DataImportErrorLog> pageResult =
                com.jiang.common.result.PageResult.of(1L, 1, 20, List.of(error));
        when(dataImportService.getImportErrors(eq(1L), eq(1), eq(20))).thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/import/errors")
                        .header("Authorization", "Bearer valid_token")
                        .param("importId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].recordId").value("kp001"))
                .andExpect(jsonPath("$.data.records[0].errorReason").value("ID 冲突"));
    }
}
