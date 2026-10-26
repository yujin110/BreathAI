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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 계정별 분석 이력, 이력 삭제, 같은 파일 재사용 기준, 군집화 설정 전달 통합 시험
// ApiIntegrationTest와 같은 테스트 DB를 쓰므로 계정·파일·기간을 시험마다 따로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisHistoryIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";

    @Autowired private MockMvc mvc;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("같은 파일은 옵션이 모두 같을 때만 기존 결과를 돌려주고, 하나라도 다르면 새로 분석한다")
    void reuseOnlyWhenOptionsMatch() throws Exception {
        String admin = adminToken();
        byte[] content = content();

        Long first = upload(admin, content, Map.of("periodStartDate", "2026-08-01", "periodEndDate", "2026-08-31"));
        Long same = upload(admin, content, Map.of("periodStartDate", "2026-08-01", "periodEndDate", "2026-08-31"));
        Long otherPeriod = upload(admin, content, Map.of("periodStartDate", "2026-08-01", "periodEndDate", "2026-08-15"));
        Long noMasking = upload(admin, content, Map.of("periodStartDate", "2026-08-01", "periodEndDate", "2026-08-31",
                "isMaskingEnabled", "false"));
        Long clustering = upload(admin, content, Map.of("periodStartDate", "2026-08-01", "periodEndDate", "2026-08-31",
                "minClusterSize", "3"));

        assertThat(same).isEqualTo(first);
        assertThat(List.of(otherPeriod, noMasking, clustering)).doesNotContain(first).doesNotHaveDuplicates();
        // 같은 분석을 다시 요청해도 내 이력에는 한 줄만 남는다
        assertThat(rows(admin, "mine", null, first)).hasSize(1);
    }

    @Test
    @DisplayName("다른 사람이 올린 파일을 올리면 같은 분석이 내 이력에도 생기고, 전체 보기에는 요청자별로 나온다")
    void sharedAnalysisAppearsInBothHistories() throws Exception {
        String a = adminToken();
        String b = adminToken();
        byte[] content = content();

        Long analysisId = upload(a, content, Map.of());
        assertThat(upload(b, content, Map.of())).isEqualTo(analysisId);

        List<Map<String, Object>> mineOfB = rows(b, "mine", null, analysisId);
        assertThat(mineOfB).hasSize(1);
        assertThat(mineOfB.get(0).get("isMine")).isEqualTo(true);
        assertThat(rows(a, "mine", null, analysisId)).hasSize(1);

        List<Map<String, Object>> all = rows(b, "all", null, analysisId);
        assertThat(all).hasSize(2);
        assertThat(all).extracting(row -> row.get("isMine")).containsExactlyInAnyOrder(true, false);
        assertThat(all).extracting(row -> row.get("requestedBy")).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("이력 삭제는 내 줄만 지우고, 분석 결과와 전체 대시보드는 그대로 둔다")
    void deleteRemovesOnlyMyRow() throws Exception {
        String a = adminToken();
        String b = adminToken();
        // 다른 시험의 분석과 섞이지 않도록 먼 기간을 쓴다
        Long analysisId = upload(a, content(), Map.of("periodStartDate", "2032-03-01", "periodEndDate", "2032-03-31"));
        callback(analysisId, summaryCallback(7));

        mvc.perform(delete("/api/v1/dashboard/analyze/history/" + analysisId).header(HttpHeaders.AUTHORIZATION, bearer(b)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/dashboard/analyze/history/" + analysisId).header(HttpHeaders.AUTHORIZATION, bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("분석 이력이 삭제되었습니다."));
        mvc.perform(delete("/api/v1/dashboard/analyze/history/" + analysisId).header(HttpHeaders.AUTHORIZATION, bearer(a)))
                .andExpect(status().isNotFound());

        assertThat(rows(a, "mine", null, analysisId)).isEmpty();
        assertThat(rows(a, "all", null, analysisId)).isEmpty();
        mvc.perform(get("/api/v1/dashboard/analyze/" + analysisId + "/result").header(HttpHeaders.AUTHORIZATION, bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemStatus.totalLogCount").value(7));

        String period = "/api/v1/dashboard/analyze/result?startDate=2032-03-01&endDate=2032-03-31";
        mvc.perform(get(period + "&scope=all").header(HttpHeaders.AUTHORIZATION, bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemStatus.totalLogCount").value(7));
        mvc.perform(get(period).header(HttpHeaders.AUTHORIZATION, bearer(a)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("대시보드 기본(mine)은 내 이력의 최신 완료 분석을 쓰고, 없으면 404이다")
    void dashboardMineUsesMyLatestCompleted() throws Exception {
        String admin = adminToken();
        mvc.perform(get("/api/v1/dashboard/analyze/result").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());

        // 종료 시각이 초 단위라 완료 분석은 하나만 두고, 다른 사람의 최신 분석이 섞이지 않는지 본다
        Long mine = upload(admin, content(), Map.of());
        callback(mine, summaryCallback(13));
        Long others = upload(adminToken(), content(), Map.of());
        callback(others, summaryCallback(17));
        // 실패한 분석은 대시보드에 쓰지 않는다
        Long failed = upload(admin, content(), Map.of());
        callback(failed, "{\"status\":\"FAILED\",\"errorMessage\":\"test\"}");

        mvc.perform(get("/api/v1/dashboard/analyze/result?scope=mine").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemStatus.totalLogCount").value(13));
    }

    @Test
    @DisplayName("이력 상태 필터는 대소문자를 가리지 않고, 잘못된 scope·status는 400이다")
    void statusFilterAndInvalidValues() throws Exception {
        String admin = adminToken();
        Long completed = upload(admin, content(), Map.of());
        callback(completed, summaryCallback(3));
        Long failed = upload(admin, content(), Map.of());
        callback(failed, "{\"status\":\"FAILED\",\"errorMessage\":\"test\"}");

        assertThat(rows(admin, "mine", "COMPLETED", completed)).hasSize(1);
        assertThat(rows(admin, "mine", "COMPLETED", failed)).isEmpty();
        assertThat(rows(admin, "MINE", "fail", failed)).hasSize(1);

        for (String query : List.of("scope=everyone", "status=DONE")) {
            mvc.perform(get("/api/v1/dashboard/analyze/history?" + query).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/dashboard/analyze/result?scope=everyone").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USER는 내 이력이 비어 있고, 전체 보기로 다른 사람의 분석을 본다")
    void userSeesOthersOnlyInAll() throws Exception {
        Long analysisId = upload(adminToken(), content(), Map.of());
        String user = userToken();

        mvc.perform(get("/api/v1/dashboard/analyze/history").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));
        List<Map<String, Object>> all = rows(user, "all", null, analysisId);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("isMine")).isEqualTo(false);
    }

    @Test
    @DisplayName("군집화 설정은 범위를 검사해 AI로 넘기고, 비우면 보내지 않는다")
    void clusteringOptionsAreValidatedAndForwarded() throws Exception {
        String admin = adminToken();
        for (Map<String, String> params : List.of(Map.of("minClusterSize", "1"), Map.of("minSamples", "0"))) {
            MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/dashboard/analyze/upload").file(file(content()));
            params.forEach(request::param);
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isBadRequest());
        }

        Long withOptions = upload(admin, content(), Map.of("minClusterSize", "3", "minSamples", "2"));
        verify(webClientUtil, timeout(5000)).sendPipeline(anyString(), eq(withOptions), any(), anyString(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), eq(3), eq(2), any());
        Long withoutOptions = upload(admin, content(), Map.of());
        verify(webClientUtil, timeout(5000)).sendPipeline(anyString(), eq(withoutOptions), any(), anyString(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), isNull(), isNull(), any());

        List<Map<String, Object>> row = rows(admin, "mine", null, withOptions);
        assertThat(row.get(0).get("minClusterSize")).isEqualTo(3);
        assertThat(row.get(0).get("minSamples")).isEqualTo(2);
    }

    @Test
    @DisplayName("질문이나 답변이 빈 FAQ 후보는 저장하지 않는다")
    void blankCandidatesAreSkipped() throws Exception {
        String admin = adminToken();
        Long analysisId = upload(admin, content(), Map.of());
        callback(analysisId, "{\"status\":\"COMPLETED\",\"clusteringView\":{\"clusterNames\":[{\"clusterLabel\":3,"
                + "\"name\":\"환승\",\"topKeywords\":[\"환승\"],\"size\":5}],\"points\":[]},\"faqCandidates\":["
                + candidate("환승은 어떻게 하나요?", "안내 표지를 따라 이동하세요.") + ","
                + candidate("", "질문이 빈 후보") + "," + candidate("답변이 빈 후보", "  ") + "]}");

        mvc.perform(get("/api/v1/faq/recommendations/" + analysisId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendations.length()").value(1));
    }

    @Test
    @DisplayName("탈퇴하면 그 사람의 이력만 지워지고 분석 결과는 남는다")
    void quitRemovesHistoryButKeepsAnalysis() throws Exception {
        String leaving = adminToken();
        Long analysisId = upload(leaving, content(), Map.of());
        callback(analysisId, summaryCallback(5));

        mvc.perform(delete("/api/v1/auth/quit").header(HttpHeaders.AUTHORIZATION, bearer(leaving)))
                .andExpect(status().isOk());

        String other = adminToken();
        assertThat(rows(other, "all", null, analysisId)).isEmpty();
        mvc.perform(get("/api/v1/dashboard/analyze/" + analysisId + "/result").header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk());
    }

    // 이력에서 해당 분석의 줄만 골라낸다, 다른 시험의 분석도 섞여 있어 넉넉히 받는다
    private List<Map<String, Object>> rows(String token, String scope, String status, Long analysisId) throws Exception {
        String query = "/api/v1/dashboard/analyze/history?page=0&size=200&scope=" + scope
                + (status != null ? "&status=" + status : "");
        String history = body(mvc.perform(get(query).header(HttpHeaders.AUTHORIZATION, bearer(token)))
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

    // 전체 건수만 다르게 한 완료 콜백, 어느 분석이 조회됐는지 이 값으로 구분한다
    private String summaryCallback(int total) {
        return "{\"status\":\"COMPLETED\",\"systemStatus\":{\"totalLogCount\":" + total
                + ",\"samplingStatus\":{\"correct\":0,\"lowQuality\":0,\"unanswer\":" + total + "}},"
                + "\"performanceMetrics\":{\"currentUnanswerRate\":100.0},"
                + "\"clusteringView\":{\"clusterNames\":[],\"points\":[]},\"faqCandidates\":[]}";
    }

    private String candidate(String question, String answer) {
        return "{\"clusterLabel\":3,\"candidateType\":\"NEW\",\"standardQuestion\":\"" + question + "\","
                + "\"answerDraft\":\"" + answer + "\",\"qType\":null,\"category\":null,\"similarQuestions\":[],"
                + "\"occurrenceCount\":5,\"representativeKeywords\":[\"환승\"],\"synonyms\":[],\"matchedFaqs\":[]}";
    }

    // 파일 내용이 매번 달라야 다른 시험의 업로드와 같은 파일로 처리되지 않는다
    private byte[] content() {
        return ("번호,질문,날짜\n1,환승은 어떻게 하나요 " + UUID.randomUUID() + ",2026-08-01\n").getBytes(StandardCharsets.UTF_8);
    }

    private MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "history.csv", "text/csv", content);
    }

    private String adminToken() throws Exception {
        return token(newEmail("history-admin"), ADMIN_CODE);
    }

    private String userToken() throws Exception {
        return token(newEmail("history-user"), null);
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
