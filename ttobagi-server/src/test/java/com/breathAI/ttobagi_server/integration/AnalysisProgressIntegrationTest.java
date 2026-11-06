package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisProgressService;
import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 분석 세부 진행 정보: AI GET /jobs/{id}의 progress 저장·조회·SSE (AI 연동 계약 v3 4절, 03_progress.json)
// AI 호출은 가짜(WebClientUtil 대역)이고, 조회 주기를 기다리지 않고 poll()을 직접 부른다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisProgressIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnalysisProgressService progressService;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("진행 예시를 받으면 상태 조회 progressDetail로 그대로 내려주고 상태는 바꾸지 않는다")
    void progressFixtureIsStoredAndReturned() throws Exception {
        String admin = adminToken();
        Long id = acceptedAnalysis(admin);
        when(webClientUtil.job(id)).thenReturn(aiJob(fixture()));

        progressService.poll();

        checkStatus(admin, id)
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.progressDetail.phase").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.progressDetail.completedUnits").value(32))
                .andExpect(jsonPath("$.data.progressDetail.totalUnits").value(1200))
                .andExpect(jsonPath("$.data.progressDetail.unit").value("LOG"))
                .andExpect(jsonPath("$.data.progressDetail.elapsedSeconds").value(110.5))
                .andExpect(jsonPath("$.data.progressDetail.sequence").value(15))
                .andExpect(jsonPath("$.data.progressDetail.updatedAt").exists())
                .andExpect(jsonPath("$.data.progressDetail.delayed").value(false));
    }

    @Test
    @DisplayName("sequence가 같거나 작은 응답, AI 실패, progress 없음은 저장된 값을 바꾸지 않는다")
    void staleOrMissingProgressIsIgnored() throws Exception {
        String admin = adminToken();
        Long id = acceptedAnalysis(admin);
        when(webClientUtil.job(id)).thenReturn(aiJob(fixture()));
        progressService.poll();

        when(webClientUtil.job(id)).thenReturn(aiJob(progressJson("CLUSTERING", 14)));
        progressService.poll();
        when(webClientUtil.job(id)).thenReturn(aiJob(progressJson("CLUSTERING", 15)));
        progressService.poll();
        when(webClientUtil.job(id)).thenThrow(new IllegalStateException("AI 연결 실패"));
        progressService.poll();
        doReturn(aiJob("{\"analysisId\":1,\"status\":\"RUNNING\",\"callback\":\"NONE\",\"progress\":null}"))
                .when(webClientUtil).job(id);
        progressService.poll();

        checkStatus(admin, id)
                .andExpect(jsonPath("$.data.progressDetail.phase").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.progressDetail.sequence").value(15));

        // 더 큰 sequence는 반영한다
        doReturn(aiJob(progressJson("CLUSTERING", 16))).when(webClientUtil).job(id);
        progressService.poll();
        checkStatus(admin, id).andExpect(jsonPath("$.data.progressDetail.phase").value("CLUSTERING"));
    }

    @Test
    @DisplayName("진행 정보가 60초 넘게 안 바뀌면 지연으로 표시하고, 끝난 분석은 지연으로 보지 않는다")
    void delayedAfterSixtySeconds() throws Exception {
        String admin = adminToken();
        Long id = acceptedAnalysis(admin);
        when(webClientUtil.job(id)).thenReturn(aiJob(fixture()));
        progressService.poll();

        jdbcTemplate.update("UPDATE gold_analysis_job SET progress_updated_at = NOW() - INTERVAL 30 SECOND WHERE analysis_id=?", id);
        checkStatus(admin, id).andExpect(jsonPath("$.data.progressDetail.delayed").value(false));
        jdbcTemplate.update("UPDATE gold_analysis_job SET progress_updated_at = NOW() - INTERVAL 61 SECOND WHERE analysis_id=?", id);
        checkStatus(admin, id)
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.progressDetail.delayed").value(true));

        callback(id, completedCallback());
        checkStatus(admin, id)
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progressDetail.phase").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.progressDetail.delayed").value(false));
    }

    @Test
    @DisplayName("AI가 접수하지 않았거나, 취소를 요청했거나, 끝난 분석은 AI에 묻지 않는다")
    void onlyAcceptedRunningAnalysesArePolled() throws Exception {
        String admin = adminToken();
        Long notSent = upload(admin);
        Long canceling = acceptedAnalysis(admin);
        jdbcTemplate.update("UPDATE gold_analysis_job SET cancel_requested_at = NOW() WHERE analysis_id=?", canceling);
        Long completed = acceptedAnalysis(admin);
        callback(completed, completedCallback());

        clearInvocations(webClientUtil);
        progressService.poll();

        verify(webClientUtil, never()).job(notSent);
        verify(webClientUtil, never()).job(canceling);
        verify(webClientUtil, never()).job(completed);
    }

    @Test
    @DisplayName("SSE에 연결하면 상태와 함께 현재 진행 정보를 progress 이벤트로 보낸다")
    void streamSendsCurrentProgress() throws Exception {
        String admin = adminToken();
        Long id = acceptedAnalysis(admin);
        when(webClientUtil.job(id)).thenReturn(aiJob(fixture()));
        progressService.poll();

        MvcResult stream = mvc.perform(get("/api/v1/dashboard/analyze/stream/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        String events = body(stream);
        assertThat(events).contains("event:status").contains("event:progress")
                .contains("\"phase\":\"EMBEDDING\"").contains("\"sequence\":15");
    }

    private WebClientUtil.AiJob aiJob(String json) throws Exception {
        return objectMapper.readValue(json, WebClientUtil.AiJob.class);
    }

    private String fixture() throws Exception {
        return new ClassPathResource("fixtures/analysis-contract-v2/03_progress.json").getContentAsString(StandardCharsets.UTF_8);
    }

    private String progressJson(String phase, int sequence) {
        return "{\"analysisId\":1,\"status\":\"RUNNING\",\"callback\":\"NONE\",\"progress\":{\"phase\":\"" + phase
                + "\",\"completedUnits\":null,\"totalUnits\":null,\"unit\":null,\"elapsedSeconds\":200.0,"
                + "\"heartbeatAt\":\"2026-11-05T15:12:00Z\",\"sequence\":" + sequence + "}}";
    }

    private String completedCallback() {
        return "{\"status\":\"COMPLETED\",\"systemStatus\":{\"totalLogCount\":1,"
                + "\"samplingStatus\":{\"correct\":0,\"lowQuality\":0,\"unanswer\":1}},"
                + "\"clusteringView\":{\"clusterNames\":[],\"points\":[]},\"faqCandidates\":[]}";
    }

    // AI가 접수한 분석 (전송은 가짜라 접수 상태를 직접 넣는다)
    private Long acceptedAnalysis(String token) throws Exception {
        Long id = upload(token);
        jdbcTemplate.update("UPDATE gold_analysis_job SET ai_submission_state='ACCEPTED' WHERE analysis_id=?", id);
        return id;
    }

    private ResultActions checkStatus(String token, Long analysisId) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/analyze/" + analysisId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    // 파일 내용이 매번 달라야 다른 시험의 업로드와 같은 파일로 처리되지 않는다
    private Long upload(String token) throws Exception {
        byte[] content = ("번호,질문,날짜\n1,환승은 어떻게 하나요 " + UUID.randomUUID() + ",2026-11-05\n")
                .getBytes(StandardCharsets.UTF_8);
        String response = body(mvc.perform(multipart("/api/v1/dashboard/analyze/upload")
                        .file(new MockMultipartFile("file", "progress.csv", "text/csv", content))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        return ((Number) JsonPath.read(response, "$.data.analysisId")).longValue();
    }

    private void callback(Long analysisId, String json) throws Exception {
        mvc.perform(withJson(post("/api/v1/dashboard/analyze/callback/" + analysisId), json)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CALLBACK_TOKEN))
                .andExpect(status().isOk());
    }

    private String adminToken() throws Exception {
        String email = "progress-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@seoulmetro.co.kr";
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
