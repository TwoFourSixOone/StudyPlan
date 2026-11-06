package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.ImportErrorVO;
import com.jiang.vo.ImportLogListVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminImportLogController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminImportLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private AdminListQueryService adminListQueryService;

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    @Test
    void should_returnImportLogList_when_validRequest() throws Exception {
        stubJwtAuth();
        ImportLogListVO vo = new ImportLogListVO();
        vo.setId(1L);
        vo.setDataType("knowledge_point");
        vo.setFileSource("upload.json");
        vo.setSuccessCount(100);
        vo.setFailCount(2);
        vo.setSkipCount(3);
        vo.setImportedAt(LocalDateTime.now());

        PageResult<ImportLogListVO> pageResult = PageResult.of(1L, 1, 20, Arrays.asList(vo));
        when(adminListQueryService.getImportLogList(any(), any(), any(), eq(1), eq(20)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/import-logs")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].fileSource").value("upload.json"))
                .andExpect(jsonPath("$.data.records[0].successCount").value(100));
    }

    @Test
    void should_returnImportErrorList_when_validImportId() throws Exception {
        stubJwtAuth();
        ImportErrorVO vo = new ImportErrorVO();
        vo.setId(1L);
        vo.setImportId(100L);
        vo.setRecordId("rec_001");
        vo.setErrorReason("字段缺失");
        vo.setCreateTime(LocalDateTime.now());

        PageResult<ImportErrorVO> pageResult = PageResult.of(1L, 1, 20, Arrays.asList(vo));
        when(adminListQueryService.getImportErrorList(eq(100L), eq(1), eq(20)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/import-logs/errors")
                        .header("Authorization", "Bearer valid_token")
                        .param("importId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].errorReason").value("字段缺失"));
    }
}
