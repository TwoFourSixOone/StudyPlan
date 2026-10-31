package com.jiang.controller;

import com.jiang.security.JwtUtil;
import com.jiang.service.SubjectService;
import com.jiang.vo.SubjectVO;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SubjectController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-10-31
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private SubjectService subjectService;

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn("default");
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    @Test
    void should_returnSubjectList_when_subjectsExist() throws Exception {
        stubJwtAuth();
        List<SubjectVO> subjects = List.of(
            SubjectVO.builder().subjectId(1L).name("数学").stage("高中")
                .description("数学科目").activeKnowledgePointCount(5).build(),
            SubjectVO.builder().subjectId(2L).name("英语").stage("高中")
                .description("英语科目").activeKnowledgePointCount(3).build()
        );
        when(subjectService.getAvailableSubjects()).thenReturn(subjects);

        mockMvc.perform(get("/api/student/subject")
                        .header("Authorization", "Bearer valid_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("数学"))
                .andExpect(jsonPath("$.data[0].activeKnowledgePointCount").value(5))
                .andExpect(jsonPath("$.data[1].name").value("英语"));
    }

    @Test
    void should_return401_when_noJwt() throws Exception {
        mockMvc.perform(get("/api/student/subject"))
                .andExpect(status().isUnauthorized());
    }
}
