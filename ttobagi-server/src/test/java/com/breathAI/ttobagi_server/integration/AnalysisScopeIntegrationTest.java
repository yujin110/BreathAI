package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisMode;
import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 분석 모드·범위: AI 연동 계약 v2 예시 JSON 저장·조회, 예전 형식 결과, 모드별 재사용
// ApiIntegrationTest와 같은 테스트 DB를 쓰므로 계정·파일을 시험마다 따로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisScopeIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";
    private static final Map<String, String> NOVEMBER = Map.of("periodStartDate", "2026-11-01", "periodEndDate", "2026-11-30");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("새 분석은 기본 모드(IMPROVEMENT)로 만들고 AI 요청에도 같은 모드를 보낸다")
    void newAnalysisUsesDefaultMode() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), Map.of());

        verify(webClientUtil, timeout(5000)).sendPipeline(anyString(), eq(id), any(), anyString(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), any(), any(), eq(AnalysisMode.IMPROVEMENT), any());
        assertThat(mode(id)).isEqualTo("IMPROVEMENT");
        assertThat(rows(admin, id).get(0).get("analysisMode")).isEqualTo("IMPROVEMENT");
    }

    @Test
    @DisplayName("정상 완료 예시: 범위는 그대로 저장하고 전체 통계는 10건, 좌표·후보는 대상 5건 기준이다")
    void completedFixtureIsStoredAndReturned() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), NOVEMBER);
        callback(id, fixture("01_completed.json"));

        result(admin, id)
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.resultSchemaVersion").value(2))
                .andExpect(jsonPath("$.data.analysisMode").value("IMPROVEMENT"))
                .andExpect(jsonPath("$.data.completionReason").value("RESULTS_GENERATED"))
                .andExpect(jsonPath("$.data.analysisScope.totalLogCount").value(10))
                .andExpect(jsonPath("$.data.analysisScope.targetLogCount").value(5))
                .andExpect(jsonPath("$.data.analysisScope.excludedByModeCount").value(5))
                .andExpect(jsonPath("$.data.analysisScope.clusteredLogCount").value(5))
                .andExpect(jsonPath("$.data.analysisScope.faqClusterCount").value(1))
                .andExpect(jsonPath("$.data.analysisScope.classificationSourceCounts.EXPLICIT_LOG_TYPE").value(10))
                .andExpect(jsonPath("$.data.analysisScope.classificationSourceCounts.LEGACY_DEFAULT").value(0))
                .andExpect(jsonPath("$.data.analysisScope.keywordScope").value("ANALYSIS_TARGET"))
                .andExpect(jsonPath("$.data.systemStatus.totalLogCount").value(10))
                .andExpect(jsonPath("$.data.systemStatus.samplingStatus.correct").value(5))
                .andExpect(jsonPath("$.data.systemStatus.samplingStatus.lowQuality").value(2))
                .andExpect(jsonPath("$.data.systemStatus.samplingStatus.unanswer").value(3))
                .andExpect(jsonPath("$.data.performanceMetrics.currentUnanswerRate").value(30.0))
                .andExpect(jsonPath("$.data.usageStats[0].totalCnt").value(10))
                .andExpect(jsonPath("$.data.clusteringView.points", hasSize(5)))
                .andExpect(jsonPath("$.data.faqCandidates", hasSize(1)));

        dashboard(admin, "")
                .andExpect(jsonPath("$.data.analysisModes", contains("IMPROVEMENT")))
                .andExpect(jsonPath("$.data.systemStatus.totalLogCount").value(10));
    }

    @Test
    @DisplayName("대상 0건 예시: 정상 완료로 저장하고 전체 통계는 남기며 좌표·후보는 비어 있다")
    void noTargetFixtureCompletesEmpty() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), Map.of());
        callback(id, fixture("02_no_targets.json"));

        result(admin, id)
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completionReason").value("NO_TARGET_LOGS"))
                .andExpect(jsonPath("$.data.analysisScope.targetLogCount").value(0))
                .andExpect(jsonPath("$.data.analysisScope.excludedByModeCount").value(10))
                .andExpect(jsonPath("$.data.systemStatus.totalLogCount").value(10))
                .andExpect(jsonPath("$.data.systemStatus.samplingStatus.correct").value(10))
                .andExpect(jsonPath("$.data.clusteringView.points", hasSize(0)))
                .andExpect(jsonPath("$.data.clusteringView.clusterNames", hasSize(0)))
                .andExpect(jsonPath("$.data.faqCandidates", hasSize(0)));
    }

    @Test
    @DisplayName("모드를 보내지 않은 예전 AI 결과는 ALL로 확정하고 범위는 추정하지 않는다")
    void legacyResultIsAll() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), Map.of());
        assertThat(mode(id)).isEqualTo("IMPROVEMENT");
        callback(id, legacyCallback(4));

        result(admin, id)
                .andExpect(jsonPath("$.data.resultSchemaVersion").value(1))
                .andExpect(jsonPath("$.data.analysisMode").value("ALL"))
                .andExpect(jsonPath("$.data.completionReason").doesNotExist())
                .andExpect(jsonPath("$.data.analysisScope").doesNotExist())
                .andExpect(jsonPath("$.data.systemStatus.totalLogCount").value(4));
        assertThat(mode(id)).isEqualTo("ALL");
    }

    @Test
    @DisplayName("업로드에서 모드를 고르면 그 모드로 분석을 만들고 AI에도 그 모드를 보낸다")
    void selectedModeIsUsed() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), Map.of("analysisMode", "UNANSWER_ONLY"));

        verify(webClientUtil, timeout(5000)).sendPipeline(anyString(), eq(id), any(), anyString(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), any(), any(), eq(AnalysisMode.UNANSWER_ONLY), any());
        assertThat(mode(id)).isEqualTo("UNANSWER_ONLY");
        assertThat(rows(admin, id).get(0).get("analysisMode")).isEqualTo("UNANSWER_ONLY");
    }

    @Test
    @DisplayName("고른 모드로 재사용을 판단한다: 다른 모드는 새 분석, 같은 모드는 기존 결과")
    void selectedModeDecidesReuse() throws Exception {
        String admin = adminToken();
        byte[] content = content();
        Long all = upload(admin, content, Map.of("analysisMode", "ALL"));
        Long byDefault = upload(admin, content, Map.of());

        assertThat(byDefault).isNotEqualTo(all);
        assertThat(mode(byDefault)).isEqualTo("IMPROVEMENT");
        assertThat(upload(admin, content, Map.of("analysisMode", "ALL"))).isEqualTo(all);
        assertThat(upload(admin, content, Map.of("analysisMode", "IMPROVEMENT"))).isEqualTo(byDefault);
    }

    @Test
    @DisplayName("잘못된 모드(소문자·빈 값·없는 이름)는 400이고 분석을 만들지 않는다")
    void invalidModeIsRejected() throws Exception {
        String admin = adminToken();
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_analysis_job", Integer.class);

        for (String mode : List.of("improvement", "", "BOGUS")) {
            mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file(content()))
                            .param("analysisMode", mode)
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isBadRequest());
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_analysis_job", Integer.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("같은 파일·옵션이라도 모드가 다르면 새로 분석하고, 모드까지 같으면 기존 결과를 쓴다")
    void reuseRequiresSameMode() throws Exception {
        String admin = adminToken();
        byte[] content = content();
        Long legacy = upload(admin, content, NOVEMBER);
        callback(legacy, legacyCallback(2));
        assertThat(mode(legacy)).isEqualTo("ALL");

        Long improvement = upload(admin, content, NOVEMBER);
        assertThat(improvement).isNotEqualTo(legacy);
        assertThat(mode(improvement)).isEqualTo("IMPROVEMENT");
        assertThat(upload(admin, content, NOVEMBER)).isEqualTo(improvement);

        // 기간 합산 조회는 두 모드가 섞여 있음을 알려 준다
        callback(improvement, fixture("01_completed.json"));
        dashboard(admin, "?startDate=2026-11-01&endDate=2026-11-30")
                .andExpect(jsonPath("$.data.analysisModes", contains("ALL", "IMPROVEMENT")));
    }

    @Test
    @DisplayName("결과 형식 v2인데 범위가 빠져도 콜백은 받고 받은 값만 저장한다")
    void incompleteV2IsAcceptedAsIs() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), Map.of());
        callback(id, "{\"status\":\"COMPLETED\",\"resultSchemaVersion\":2,\"analysisMode\":\"IMPROVEMENT\","
                + "\"systemStatus\":{\"totalLogCount\":1,\"samplingStatus\":{\"correct\":0,\"lowQuality\":0,\"unanswer\":1}},"
                + "\"clusteringView\":{\"clusterNames\":[],\"points\":[]},\"faqCandidates\":[]}");

        result(admin, id)
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.resultSchemaVersion").value(2))
                .andExpect(jsonPath("$.data.analysisMode").value("IMPROVEMENT"))
                .andExpect(jsonPath("$.data.analysisScope").doesNotExist());
    }

    private String mode(Long analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT analysis_mode FROM gold_analysis_job WHERE analysis_id = ?", String.class, analysisId);
    }

    private String fixture(String name) throws Exception {
        return new ClassPathResource("fixtures/analysis-contract-v2/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    // 결과 형식 v2 이전 AI의 완료 콜백 (모드·범위 없음)
    private String legacyCallback(int total) {
        return "{\"status\":\"COMPLETED\",\"systemStatus\":{\"totalLogCount\":" + total
                + ",\"samplingStatus\":{\"correct\":0,\"lowQuality\":0,\"unanswer\":" + total + "}},"
                + "\"performanceMetrics\":{\"currentUnanswerRate\":100.0},"
                + "\"usageStats\":[{\"statDate\":\"2026-11-02\",\"totalCnt\":" + total + ",\"answerCnt\":0,"
                + "\"lowQualityCount\":0,\"unanswerCnt\":" + total + "}],"
                + "\"clusteringView\":{\"clusterNames\":[],\"points\":[]},\"faqCandidates\":[]}";
    }

    private ResultActions result(String token, Long analysisId) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/analyze/" + analysisId + "/result")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private ResultActions dashboard(String token, String query) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/analyze/result" + query)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    // 내 이력에서 해당 분석의 줄만 골라낸다
    private List<Map<String, Object>> rows(String token, Long analysisId) throws Exception {
        String history = body(mvc.perform(get("/api/v1/dashboard/analyze/history?page=0&size=200&scope=mine")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn());
        return JsonPath.read(history, "$.data.history[?(@.analysisId == " + analysisId + ")]");
    }

    private Long upload(String token, byte[] content, Map<String, String> params) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/dashboard/analyze/upload").file(file(content));
        params.forEach(request::param);
        String response = body(mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        return ((Number) JsonPath.read(response, "$.data.analysisId")).longValue();
    }

    private void callback(Long analysisId, String json) throws Exception {
        mvc.perform(withJson(post("/api/v1/dashboard/analyze/callback/" + analysisId), json)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CALLBACK_TOKEN))
                .andExpect(status().isOk());
    }

    // 파일 내용이 매번 달라야 다른 시험의 업로드와 같은 파일로 처리되지 않는다
    private byte[] content() {
        return ("번호,질문,날짜\n1,분실물은 어디서 찾나요 " + UUID.randomUUID() + ",2026-11-05\n").getBytes(StandardCharsets.UTF_8);
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "scope.csv", "text/csv", content);
    }

    private String adminToken() throws Exception {
        String email = "scope-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@seoulmetro.co.kr";
        TestSignup.signup(mvc, emailService, email, PASSWORD, ADMIN_CODE);
        String login = body(mvc.perform(withJson(post("/api/v1/auth/login"),
                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn());
        return JsonPath.read(login, "$.data.accessToken");
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, String json) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
