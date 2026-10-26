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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FAQ 후보 반려·수정, FAQ 직접 등록, 버전 만들기·비교·복원 통합 시험
// 복원은 운영 FAQ 전체에 영향을 주므로 한 시험 안에서 버전을 만들고 그 뒤에 바뀐 것만 확인한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqManagementIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("후보 반려는 사유를 기록하고, 반려된 후보는 다시 반려·수정·반영할 수 없다")
    void rejectCandidate() throws Exception {
        String admin = adminToken();
        Long analysisId = analysisWithCandidates(admin, "반려 후보");
        Number candidateId = candidateIds(admin, analysisId).get(0);
        String path = "/api/v1/faq/recommendations/" + candidateId + "/reject";

        mvc.perform(withJson(patch(path), "{\"reason\":\"x\"}").header(HttpHeaders.AUTHORIZATION, bearer(userToken())))
                .andExpect(status().isForbidden());
        mvc.perform(withJson(patch(path), "{\"reason\":\"운영 정책과 맞지 않음\"}").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());

        assertThat(candidateField(admin, analysisId, candidateId, "reviewStatus")).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT note FROM gold_faq_action_log WHERE candidate_id = ? AND action = 'REJECT'",
                String.class, candidateId.longValue())).isEqualTo("운영 정책과 맞지 않음");

        // 사유 없이 보내도 되고, 이미 반려된 후보는 409
        mvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isConflict());
        mvc.perform(withJson(patch("/api/v1/faq/recommendations/" + candidateId), updateBody("질문", "답변", null))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/faq/recommendations/999999999/reject").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("후보 수정은 내용을 저장하고 바뀐 칸의 전후 값을 검토 기록에 남긴다")
    void updateCandidate() throws Exception {
        String admin = adminToken();
        Long analysisId = analysisWithCandidates(admin, "수정 후보");
        Number candidateId = candidateIds(admin, analysisId).get(0);

        mvc.perform(withJson(patch("/api/v1/faq/recommendations/" + candidateId),
                        updateBody("고친 질문", "고친 답변", 188))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.standardQuestion").value("고친 질문"))
                .andExpect(jsonPath("$.data.answerDraft").value("고친 답변"))
                .andExpect(jsonPath("$.data.category").value("열차운영관련"))
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"));

        assertThat(candidateField(admin, analysisId, candidateId, "standardQuestion")).isEqualTo("고친 질문");
        String note = jdbcTemplate.queryForObject(
                "SELECT note FROM gold_faq_action_log WHERE candidate_id = ? AND action = 'EDIT'",
                String.class, candidateId.longValue());
        assertThat((String) JsonPath.read(note, "$.after.standardQuestion")).isEqualTo("고친 질문");
        assertThat((String) JsonPath.read(note, "$.before.standardQuestion")).startsWith("수정 후보");
        // 바꾸지 않은 칸(키워드)은 기록하지 않는다
        assertThat((java.util.Map<String, Object>) JsonPath.read(note, "$.before")).doesNotContainKey("keywords");

        mvc.perform(withJson(patch("/api/v1/faq/recommendations/" + candidateId), "{\"standardQuestion\":\"\",\"answerDraft\":\"a\"}")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("FAQ 직접 등록은 관리자만 할 수 있고 변경 이력에 등록으로 남는다")
    void createFaq() throws Exception {
        String admin = adminToken();
        mvc.perform(withJson(post("/api/v1/faq"), createBody("권한 없음", 176)).header(HttpHeaders.AUTHORIZATION, bearer(userToken())))
                .andExpect(status().isForbidden());
        mvc.perform(withJson(post("/api/v1/faq"), "{\"answer\":\"질문 없음\"}").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest());

        Long faqId = createFaq(admin, "직접 등록 " + UUID.randomUUID(), 176);
        mvc.perform(get("/api/v1/faq/" + faqId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("영업/제도"))
                .andExpect(jsonPath("$.data.keywords[0]").value("등록"));
        mvc.perform(get("/api/v1/faq/history?faqId=" + faqId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.histories.length()").value(1))
                .andExpect(jsonPath("$.data.histories[0].editType").value("CREATE"));
    }

    @Test
    @DisplayName("버전을 만든 뒤 바뀐 것을 비교하고, 그 버전으로 복원하면 FAQ와 후보가 되돌아가고 새 버전이 생긴다")
    void versionCompareAndRestore() throws Exception {
        String admin = adminToken();
        String mark = "복원시험 " + UUID.randomUUID().toString().substring(0, 8);
        Long kept = createFaq(admin, mark + " 수정될 FAQ", 176);
        Long removedLater = createFaq(admin, mark + " 삭제될 FAQ", 176);

        Long v1 = versionId(mvc.perform(post("/api/v1/faq/versions").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionType").value("MANUAL"))
                .andReturn());

        // v1 뒤의 변경: 수정 1, 삭제 1, 직접 등록 1, 후보 반영 1
        mvc.perform(withJson(patch("/api/v1/faq/" + kept), "{\"qType\":176,\"standardQuestion\":\"" + mark
                        + " 수정될 FAQ\",\"answer\":\"바뀐 답변\",\"keywords\":[\"등록\"]}")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/faq/" + removedLater).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        Long added = createFaq(admin, mark + " 새 FAQ", 172);
        Long analysisId = analysisWithCandidates(admin, mark + " 후보");
        Number candidateId = candidateIds(admin, analysisId).get(0);
        Long appliedFaq = applyCandidate(admin, analysisId, candidateId);

        // 현재 상태와 비교
        String current = body(mvc.perform(get("/api/v1/faq/versions/compare?baseId=" + v1).header(HttpHeaders.AUTHORIZATION, bearer(userToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetVersionId").doesNotExist())
                .andReturn());
        assertThat(ids(current, "added")).containsExactlyInAnyOrder(added, appliedFaq);
        assertThat(ids(current, "deleted")).containsExactly(removedLater);
        assertThat(ids(current, "changed")).containsExactly(kept);
        assertThat((List<String>) JsonPath.read(current, "$.data.changed[0].changes[*].field")).containsExactly("answer");
        assertThat((String) JsonPath.read(current, "$.data.changed[0].changes[0].after")).isEqualTo("바뀐 답변");

        // 버전끼리 비교해도 같다
        Long v2 = versionId(mvc.perform(post("/api/v1/faq/versions").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()).andReturn());
        String between = body(mvc.perform(get("/api/v1/faq/versions/compare?baseId=" + v1 + "&targetId=" + v2)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedCount").value(2))
                .andExpect(jsonPath("$.data.deletedCount").value(1))
                .andExpect(jsonPath("$.data.changedCount").value(1))
                .andReturn());
        assertThat(ids(between, "changed")).containsExactly(kept);

        // 복원
        mvc.perform(post("/api/v1/faq/versions/" + v1 + "/restore").header(HttpHeaders.AUTHORIZATION, bearer(userToken())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/faq/versions/" + v1 + "/restore").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(1))
                .andExpect(jsonPath("$.data.deletedCount").value(2))
                .andExpect(jsonPath("$.data.reactivatedCount").value(1))
                .andExpect(jsonPath("$.data.revertedCandidateCount").value(1))
                .andExpect(jsonPath("$.data.newVersionId").isNumber());

        mvc.perform(get("/api/v1/faq/" + kept).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.data.answer").value("통합테스트 답변"));
        mvc.perform(get("/api/v1/faq/" + removedLater).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        for (Long gone : List.of(added, appliedFaq)) {
            mvc.perform(get("/api/v1/faq/" + gone).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isNotFound());
        }
        assertThat(candidateField(admin, analysisId, candidateId, "reviewStatus")).isEqualTo("PENDING");
        mvc.perform(get("/api/v1/faq/history?faqId=" + kept).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.data.histories[0].editType").value("MANUAL"))
                .andExpect(jsonPath("$.data.histories[0].editReason").value(org.hamcrest.Matchers.endsWith("버전으로 복원")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gold_faq_action_log WHERE candidate_id = ? AND action = 'REVERT'",
                Integer.class, candidateId.longValue())).isEqualTo(1);

        // 되돌린 뒤에는 현재 상태가 v1과 같고, 다시 복원해도 바뀌는 것이 없어 새 버전을 만들지 않는다
        mvc.perform(get("/api/v1/faq/versions/compare?baseId=" + v1).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.data.addedCount").value(0))
                .andExpect(jsonPath("$.data.deletedCount").value(0))
                .andExpect(jsonPath("$.data.changedCount").value(0));
        mvc.perform(post("/api/v1/faq/versions/" + v1 + "/restore").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.newVersionId").doesNotExist());

        mvc.perform(post("/api/v1/faq/versions/999999999/restore").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());
    }

    private Long analysisWithCandidates(String token, String question) throws Exception {
        String content = "번호,질문,날짜\n1,환승 문의 " + UUID.randomUUID() + ",2026-08-01\n";
        MockMultipartFile file = new MockMultipartFile("file", "faq.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        String response = body(mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn());
        Long analysisId = ((Number) JsonPath.read(response, "$.data.analysisId")).longValue();

        String callback = "{\"status\":\"COMPLETED\",\"clusteringView\":{\"clusterNames\":[{\"clusterLabel\":3,\"name\":\"환승\","
                + "\"topKeywords\":[\"환승\"],\"size\":5}],\"points\":[]},\"faqCandidates\":[" + candidate(question + " 1") + ","
                + candidate(question + " 2") + "]}";
        mvc.perform(withJson(post("/api/v1/dashboard/analyze/callback/" + analysisId), callback)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CALLBACK_TOKEN))
                .andExpect(status().isOk());
        return analysisId;
    }

    private String candidate(String question) {
        return "{\"clusterLabel\":3,\"candidateType\":\"NEW\",\"standardQuestion\":\"" + question + "\","
                + "\"answerDraft\":\"안내 표지를 따라 이동하세요.\",\"qType\":176,\"category\":\"영업/제도\",\"similarQuestions\":[],"
                + "\"occurrenceCount\":5,\"representativeKeywords\":[\"환승\"],\"synonyms\":[],\"matchedFaqs\":[]}";
    }

    private List<Number> candidateIds(String token, Long analysisId) throws Exception {
        return JsonPath.read(recommendations(token, analysisId), "$.data.recommendations[*].candidateId");
    }

    private Object candidateField(String token, Long analysisId, Number candidateId, String field) throws Exception {
        List<Object> values = JsonPath.read(recommendations(token, analysisId),
                "$.data.recommendations[?(@.candidateId == " + candidateId + ")]." + field);
        return values.get(0);
    }

    private String recommendations(String token, Long analysisId) throws Exception {
        return body(mvc.perform(get("/api/v1/faq/recommendations/" + analysisId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private Long applyCandidate(String token, Long analysisId, Number candidateId) throws Exception {
        Number clusterId = (Number) candidateField(token, analysisId, candidateId, "clusterId");
        String apply = "{\"analysisId\":" + analysisId + ",\"clusterId\":" + clusterId + ",\"candidateId\":" + candidateId
                + ",\"finalQuestion\":\"반영 질문 " + UUID.randomUUID() + "\",\"finalAnswer\":\"반영 답변\""
                + ",\"keywords\":[\"환승\"],\"isManualPatchConfirmed\":true}";
        String response = body(mvc.perform(withJson(post("/api/v1/faq/apply"), apply).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn());
        return ((Number) JsonPath.read(response, "$.data.appliedFaqId")).longValue();
    }

    private Long createFaq(String token, String question, int qType) throws Exception {
        String response = body(mvc.perform(withJson(post("/api/v1/faq"), createBody(question, qType))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn());
        return ((Number) JsonPath.read(response, "$.data.faqId")).longValue();
    }

    private String createBody(String question, int qType) {
        return "{\"qType\":" + qType + ",\"standardQuestion\":\"" + question + "\",\"answer\":\"통합테스트 답변\",\"keywords\":[\"등록\"]}";
    }

    private String updateBody(String question, String answer, Integer qType) {
        return "{" + (qType != null ? "\"qType\":" + qType + "," : "")
                + "\"standardQuestion\":\"" + question + "\",\"answerDraft\":\"" + answer + "\"}";
    }

    private Long versionId(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(body(result), "$.data.versionId")).longValue();
    }

    private List<Long> ids(String json, String list) {
        List<Number> ids = JsonPath.read(json, "$.data." + list + "[*].faqId");
        return ids.stream().map(Number::longValue).toList();
    }

    private String adminToken() throws Exception {
        return token(newEmail("manage-admin"), ADMIN_CODE);
    }

    private String userToken() throws Exception {
        return token(newEmail("manage-user"), null);
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
