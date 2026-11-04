package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import static org.mockito.Mockito.*;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisCancellationService;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 분석 취소: 시작한 사람만 취소, 끝난 분석은 409, 취소 뒤 콜백 무시, 재업로드는 새 분석
// ApiIntegrationTest와 같은 테스트 DB를 쓰므로 계정·파일을 시험마다 따로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisCancelIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AnalysisCancellationService cancellations;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("AI 취소 실패는 503이며 취소 의도를 보존하고 복구 후에만 CANCELED가 된다")
    void aiCancellationFailureIsRecovered() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), Map.of());
        jdbcTemplate.update("UPDATE gold_analysis_job SET ai_submission_state='ACCEPTED' WHERE analysis_id=?", id);
        doThrow(new WebClientResponseException(503, "busy", null, null, null)).when(webClientUtil).cancelJob(id);
        cancel(admin, id).andExpect(status().isServiceUnavailable());
        checkStatus(admin, id).andExpect(jsonPath("$.data.status").value("PENDING"));
        callback(id, "{\"status\":\"ANALYZING\"}");
        checkStatus(admin, id).andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_requested_at FROM gold_analysis_job WHERE analysis_id=?",
                java.sql.Timestamp.class, id)).isNotNull();
        doNothing().when(webClientUtil).cancelJob(id);
        cancellations.recover();
        checkStatus(admin, id).andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    @DisplayName("AI 완료가 먼저 확정되면 결과 콜백을 보존하고 취소는 409를 반환한다")
    void terminalCallbackWinsBeforeAiCancellation() throws Exception {
        String admin = adminToken();
        Long id = upload(admin, content(), Map.of());
        jdbcTemplate.update("UPDATE gold_analysis_job SET ai_submission_state='ACCEPTED' WHERE analysis_id=?", id);
        doAnswer(inv -> {
            callback(id, summaryCallback(1));
            throw new WebClientResponseException(409, "completed", null, null, null);
        }).when(webClientUtil).cancelJob(id);
        when(webClientUtil.jobStatus(id)).thenReturn("COMPLETED");
        cancel(admin, id).andExpect(status().isConflict());
        checkStatus(admin, id).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(jdbcTemplate.queryForObject("SELECT total_log_count FROM gold_analysis_job WHERE analysis_id=?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("시작한 사람이 진행 중인 분석을 취소하면 CANCELED가 되고, 다시 취소하면 409이다")
    void starterCancels() throws Exception {
        String admin = adminToken();
        Long analysisId = upload(admin, content(), Map.of());

        checkStatus(admin, analysisId)
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.cancelable").value(true));

        cancel(admin, analysisId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("분석이 취소되었습니다."))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.cancelable").value(false));

        checkStatus(admin, analysisId)
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.message").value("분석이 취소되었습니다."))
                .andExpect(jsonPath("$.data.cancelable").value(false));
        assertThat(rows(admin, "CANCELED", analysisId)).hasSize(1);

        cancel(admin, analysisId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("취소할 수 없는 상태의 분석입니다."));
    }

    @Test
    @DisplayName("같은 파일을 쓰더라도 분석을 시작한 사람만 취소할 수 있다")
    void onlyStarterCanCancel() throws Exception {
        String a = adminToken();
        String b = adminToken();
        byte[] content = content();

        // B가 같은 파일·옵션으로 올리면 A의 분석을 함께 쓰지만, 시작한 사람은 A다
        Long shared = upload(a, content, Map.of());
        assertThat(upload(b, content, Map.of())).isEqualTo(shared);
        checkStatus(b, shared).andExpect(jsonPath("$.data.cancelable").value(false));
        cancel(b, shared)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));

        // 기간을 바꿔 B가 새로 시작한 분석은, 업로드 기록이 A의 것이어도 B만 취소한다
        Long byB = upload(b, content, Map.of("periodStartDate", "2026-08-01", "periodEndDate", "2026-08-15"));
        assertThat(byB).isNotEqualTo(shared);
        checkStatus(a, byB).andExpect(jsonPath("$.data.cancelable").value(false));
        checkStatus(b, byB).andExpect(jsonPath("$.data.cancelable").value(true));
        cancel(a, byB).andExpect(status().isForbidden());
        cancel(b, byB).andExpect(status().isOk());

        // 일반 사용자는 분석을 시작할 수 없으므로 취소할 분석도 없다
        cancel(userToken(), shared).andExpect(status().isForbidden());
        checkStatus(a, shared).andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("없는 분석은 404, 완료·실패한 분석은 409이다")
    void finishedOrMissing() throws Exception {
        String admin = adminToken();
        cancel(admin, Long.MAX_VALUE).andExpect(status().isNotFound());

        Long completed = upload(admin, content(), Map.of());
        callback(completed, summaryCallback(3));
        checkStatus(admin, completed).andExpect(jsonPath("$.data.cancelable").value(false));
        cancel(admin, completed).andExpect(status().isConflict());

        Long failed = upload(admin, content(), Map.of());
        callback(failed, "{\"status\":\"FAILED\",\"errorMessage\":\"test\"}");
        cancel(admin, failed).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("전처리·분석 중에도 취소되고, 취소 뒤 온 AI 콜백은 저장하지 않는다")
    void callbacksAfterCancelAreIgnored() throws Exception {
        String admin = adminToken();
        Long analysisId = upload(admin, content(), Map.of());
        callback(analysisId, "{\"status\":\"PREPROCESSING\"}");
        callback(analysisId, "{\"status\":\"ANALYZING\"}");
        checkStatus(admin, analysisId).andExpect(jsonPath("$.data.cancelable").value(true));

        cancel(admin, analysisId).andExpect(status().isOk());
        callback(analysisId, "{\"status\":\"ANALYZING\"}");
        callback(analysisId, summaryCallback(9));

        checkStatus(admin, analysisId).andExpect(jsonPath("$.data.status").value("CANCELED"));
        Integer saved = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gold_analysis_daily_stat WHERE analysis_id = ?", Integer.class, analysisId);
        assertThat(saved).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT total_log_count FROM gold_analysis_job WHERE analysis_id = ?", Integer.class, analysisId))
                .isNull();
    }

    @Test
    @DisplayName("취소한 파일을 다시 올리면 새로 분석한다")
    void reuploadAfterCancelStartsNewAnalysis() throws Exception {
        String admin = adminToken();
        byte[] content = content();
        Long canceled = upload(admin, content, Map.of());
        cancel(admin, canceled).andExpect(status().isOk());

        Long again = upload(admin, content, Map.of());
        assertThat(again).isNotEqualTo(canceled);
        checkStatus(admin, again)
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.cancelable").value(true));
    }

    @Test
    @DisplayName("탈퇴하면 분석을 시작한 사람 정보만 비우고 분석은 남는다")
    void quitClearsRequester() throws Exception {
        String leaving = adminToken();
        Long analysisId = upload(leaving, content(), Map.of());
        assertThat(requestedBy(analysisId)).isNotNull();

        mvc.perform(delete("/api/v1/auth/quit").header(HttpHeaders.AUTHORIZATION, bearer(leaving)))
                .andExpect(status().isOk());

        assertThat(requestedBy(analysisId)).isNull();
        String other = adminToken();
        checkStatus(other, analysisId).andExpect(jsonPath("$.data.cancelable").value(false));
        cancel(other, analysisId).andExpect(status().isForbidden());
    }

    private Long requestedBy(Long analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT requested_by FROM gold_analysis_job WHERE analysis_id = ?", Long.class, analysisId);
    }

    private ResultActions cancel(String token, Long analysisId) throws Exception {
        return mvc.perform(patch("/api/v1/dashboard/analyze/" + analysisId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private ResultActions checkStatus(String token, Long analysisId) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/analyze/" + analysisId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    // 내 이력에서 해당 분석의 줄만 골라낸다, 다른 시험의 분석도 섞여 있어 넉넉히 받는다
    private List<Map<String, Object>> rows(String token, String status, Long analysisId) throws Exception {
        String history = body(mvc.perform(get("/api/v1/dashboard/analyze/history?page=0&size=200&scope=mine&status=" + status)
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

    private String summaryCallback(int total) {
        return "{\"status\":\"COMPLETED\",\"systemStatus\":{\"totalLogCount\":" + total
                + ",\"samplingStatus\":{\"correct\":0,\"lowQuality\":0,\"unanswer\":" + total + "}},"
                + "\"performanceMetrics\":{\"currentUnanswerRate\":100.0},"
                + "\"usageStats\":[{\"statDate\":\"2026-08-01\",\"totalCnt\":" + total + ",\"answerCnt\":0,"
                + "\"lowQualityCount\":0,\"unanswerCnt\":" + total + "}],"
                + "\"clusteringView\":{\"clusterNames\":[],\"points\":[]},\"faqCandidates\":[]}";
    }

    // 파일 내용이 매번 달라야 다른 시험의 업로드와 같은 파일로 처리되지 않는다
    private byte[] content() {
        return ("번호,질문,날짜\n1,환승은 어떻게 하나요 " + UUID.randomUUID() + ",2026-08-01\n").getBytes(StandardCharsets.UTF_8);
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "cancel.csv", "text/csv", content);
    }

    private String adminToken() throws Exception {
        return token(newEmail("cancel-admin"), ADMIN_CODE);
    }

    private String userToken() throws Exception {
        return token(newEmail("cancel-user"), null);
    }

    private String token(String email, String adminCode) throws Exception {
        TestSignup.signup(mvc, emailService, email, PASSWORD, adminCode);
        String login = body(mvc.perform(withJson(post("/api/v1/auth/login"),
                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn());
        return JsonPath.read(login, "$.data.accessToken");
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, String json) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8));
    }

    private String newEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@seoulmetro.co.kr";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
