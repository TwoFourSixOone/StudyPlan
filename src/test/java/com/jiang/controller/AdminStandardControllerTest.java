package com.jiang.controller;

import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.StandardListVO;
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
 * AdminStandardController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminStandardControllerTest {

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
    void should_returnStandardList_when_validRequest() throws Exception {
        stubJwtAuth();
        StandardListVO vo = new StandardListVO();
        vo.setId(1L);
        vo.setCode("MATH-001");
        vo.setDescription("描述");
        vo.setStage("高中");
        vo.setSubjectName("数学");
        vo.setCreateTime(LocalDateTime.now());

        PageResult<StandardListVO> pageResult = PageResult.of(1L, 1, 20, Arrays.asList(vo));
        when(adminListQueryService.getStandardList(any(), any(), any(), eq(1), eq(20)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/standards")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].subjectName").value("数学"))
                .andExpect(jsonPath("$.data.records[0].stage").value("高中"));
    }

    @Test
    void should_filterBySubject_when_subjectProvided() throws Exception {
        stubJwtAuth();
        StandardListVO vo = new StandardListVO();
        vo.setId(1L);
        vo.setSubjectName("数学");
        vo.setStage("高中");
        vo.setCreateTime(LocalDateTime.now());

        PageResult<StandardListVO> pageResult = PageResult.of(1L, 1, 20, Arrays.asList(vo));
        when(adminListQueryService.getStandardList(any(), eq("数学"), any(), eq(1), eq(20)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/standards")
                        .header("Authorization", "Bearer valid_token")
                        .param("subject", "数学"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].subjectName").value("数学"));
    }
}
