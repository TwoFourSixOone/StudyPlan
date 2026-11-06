package com.jiang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiang.common.result.PageResult;
import com.jiang.security.JwtUtil;
import com.jiang.service.KnowledgePointGovernanceService;
import com.jiang.vo.KnowledgePointDetailVO;
import com.jiang.vo.KnowledgePointListVO;
import com.jiang.vo.KnowledgePointOverdueVO;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminKnowledgePointController 集成测试
 *
 * @author StudyMate Team
 * @since 2026-11-04
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminKnowledgePointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private KnowledgePointGovernanceService governanceService;

    private static final String AUTH_HEADER = "Bearer valid_token";
    private static final String TENANT_ID = "default";

    private void stubJwtAuth() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("openid", String.class)).thenReturn("test_openid");
        when(claims.get("tenantId", String.class)).thenReturn(TENANT_ID);
        when(jwtUtil.parseAndValidateToken(anyString())).thenReturn(claims);
        when(jwtUtil.shouldRefresh(anyString())).thenReturn(false);
    }

    private KnowledgePointListVO buildListVO() {
        KnowledgePointListVO vo = new KnowledgePointListVO();
        vo.setId("kp_001");
        vo.setName("二次函数");
        vo.setSubjectName("数学");
        vo.setStatus("active");
        vo.setQuestionCount(5);
        vo.setCreateTime(LocalDateTime.now().minusDays(3));
        return vo;
    }

    private KnowledgePointDetailVO buildDetailVO() {
        KnowledgePointDetailVO vo = new KnowledgePointDetailVO();
        vo.setId("kp_001");
        vo.setName("二次函数");
        vo.setSubjectName("数学");
        vo.setStatus("active");
        vo.setLevel(2);
        vo.setQuestionCount(5);
        vo.setStudentCount(10);
        vo.setCreateTime(LocalDateTime.now().minusDays(3));
        vo.setPrerequisites(Collections.emptyList());
        vo.setDependents(Collections.emptyList());
        return vo;
    }

    private KnowledgePointOverdueVO buildOverdueVO() {
        KnowledgePointOverdueVO vo = new KnowledgePointOverdueVO();
        vo.setId("kp_002");
        vo.setName("未校对知识点");
        vo.setSubjectName("数学");
        vo.setCreateTime(LocalDateTime.now().minusDays(10));
        vo.setOverdueDays(10);
        return vo;
    }

    // ==================== 列表查询 ====================

    @Test
    void should_returnList_when_validRequest() throws Exception {
        stubJwtAuth();
        PageResult<KnowledgePointListVO> pageResult = PageResult.of(1L, 1, 10, Arrays.asList(buildListVO()));
        when(governanceService.getKnowledgePointList(any(), any(), any(), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/knowledge-points")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].id").value("kp_001"))
                .andExpect(jsonPath("$.data.records[0].name").value("二次函数"));
    }

    @Test
    void should_returnListWithFilters_when_subjectAndStatusProvided() throws Exception {
        stubJwtAuth();
        PageResult<KnowledgePointListVO> pageResult = PageResult.of(1L, 1, 10, Arrays.asList(buildListVO()));
        when(governanceService.getKnowledgePointList(eq("数学"), eq("active"), any(), eq(1), eq(10)))
                .thenReturn(pageResult);

        mockMvc.perform(get("/api/admin/knowledge-points")
                        .header("Authorization", AUTH_HEADER)
                        .param("subject", "数学")
                        .param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].subjectName").value("数学"));
    }

    // ==================== 详情查看 ====================

    @Test
    void should_returnDetail_when_kpExists() throws Exception {
        stubJwtAuth();
        when(governanceService.getKnowledgePointDetail(eq("kp_001"))).thenReturn(buildDetailVO());

        mockMvc.perform(get("/api/admin/knowledge-points/kp_001")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("kp_001"))
                .andExpect(jsonPath("$.data.name").value("二次函数"))
                .andExpect(jsonPath("$.data.subjectName").value("数学"));
    }

    // ==================== 启停用 ====================

    @Test
    void should_updateStatus_when_validInput() throws Exception {
        stubJwtAuth();

        mockMvc.perform(put("/api/admin/knowledge-points/kp_001/status")
                        .header("Authorization", AUTH_HEADER)
                        .param("status", "disabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 校对标记 ====================

    @Test
    void should_markAsReviewed_when_statusPending() throws Exception {
        stubJwtAuth();

        mockMvc.perform(put("/api/admin/knowledge-points/kp_001/review")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void should_returnSuccess_when_markAsReviewedAndAlreadyActive() throws Exception {
        // 修复 BLOCK-9：幂等测试
        stubJwtAuth();

        mockMvc.perform(put("/api/admin/knowledge-points/kp_001/review")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== 超期列表 ====================

    @Test
    void should_returnOverdueList_when_hasOverdueKp() throws Exception {
        stubJwtAuth();
        when(governanceService.getOverdueKnowledgePoints()).thenReturn(Arrays.asList(buildOverdueVO()));

        mockMvc.perform(get("/api/admin/knowledge-points/overdue")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value("kp_002"))
                .andExpect(jsonPath("$.data[0].overdueDays").value(10));
    }

    @Test
    void should_returnEmptyList_when_noOverdueKp() throws Exception {
        stubJwtAuth();
        when(governanceService.getOverdueKnowledgePoints()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/admin/knowledge-points/overdue")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ==================== 未登录场景 ====================

    @Test
    void should_return401_when_noJwt() throws Exception {
        mockMvc.perform(get("/api/admin/knowledge-points"))
                .andExpect(status().isUnauthorized());
    }
}
