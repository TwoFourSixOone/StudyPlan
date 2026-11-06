package com.jiang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.AdminListQueryService;
import com.jiang.vo.QuestionListVO;
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
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminQuestionController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminQuestionControllerTest {

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
    void should_returnQuestionList_when_validRequest() throws Exception {
        stubJwtAuth();
        QuestionListVO vo = new QuestionListVO();
        vo.setId(1L);
        vo.setContent("题干");
        vo.setQuestionType("choice");
        vo.setDifficulty("basic");
        vo.setKnowledgePointName("二次函数");
        vo.setCreateTime(LocalDateTime.now());

        PageResult<QuestionListVO> pageResult = PageResult.of(1L, 1, 20, Arrays.asList(vo));
        when(adminListQueryService.getQuestionList(any(), any(), any(), any(), any(), any(), eq(1), eq(20)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/questions")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].questionType").value("choice"));
    }

    @Test
    void should_returnEmptyList_when_noData() throws Exception {
        stubJwtAuth();
        PageResult<QuestionListVO> pageResult = PageResult.of(0L, 1, 20, Collections.emptyList());
        when(adminListQueryService.getQuestionList(any(), any(), any(), any(), any(), any(), eq(1), eq(20)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/questions")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    void should_return401_when_noJwt() throws Exception {
        mockMvc.perform(get("/api/admin/questions"))
                .andExpect(status().isUnauthorized());
    }
}
