package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 확장(EXPAND) 후보의 대상 FAQ를 운영 FAQ ID(matchedFaqId)로 찾는지 확인
// 원본 순번(seq)이 없는 FAQ도 확장할 수 있어야 하고, ID가 없는 예전 콜백은 지금처럼 순번으로 찾는다
// 테스트 DB를 다른 시험과 함께 쓰므로 원본 순번은 무작위 큰 값을 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpandTargetIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";
    private static final int Q_TYPE = 176;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        String email = "expand-" + UUID.randomUUID().toString().substring(0, 8) + "@seoulmetro.co.kr";
        TestSignup.signup(mvc, emailService, email, PASSWORD, ADMIN_CODE);
        admin = JsonPath.read(body(mvc.perform(withJson(post("/api/v1/auth/login"),
                Map.of("email", email, "password", PASSWORD))).andExpect(status().isOk())), "$.data.accessToken");
    }

    @Test
    @DisplayName("V10: matched_faq_id 칸이 생기고 원본 순번은 비워 둘 수 있다")
    void migrationAddsFaqIdAndAllowsNullSeq() {
        assertThat(column("matched_faq_id", "DATA_TYPE")).isEqualTo("bigint");
        assertThat(column("matched_faq_id", "IS_NULLABLE")).isEqualTo("YES");
        assertThat(column("matched_faq_seq_num", "IS_NULLABLE")).isEqualTo("YES");
    }

    @Test
    @DisplayName("원본 순번이 없는 FAQ(직접 등록)를 ID로 확장한다: 질문·답변 교체, 키워드 합침, 새 FAQ 없음, 이력 EXPAND")
    void expandsFaqWithoutSourceSeq() throws Exception {
        String original = "직접 등록 FAQ " + UUID.randomUUID();
        long target = createFaq(original, List.of("등록", "기존"));
        assertThat(jdbcTemplate.queryForObject("SELECT source_seq_num FROM gold_faq WHERE faq_id = ?",
                Integer.class, target)).isNull();

        Long analysisId = analysis(candidate("EXPAND", "확장 질문", List.of(match(target, null, 0.91))));
        Number candidateId = onlyCandidateId(analysisId);

        // 후보 목록에 대상 FAQ의 ID와 질문이 나온다
        String recommendations = recommendations(analysisId);
        assertThat(((Number) read(recommendations, "$.data.recommendations[0].matchedFaqs[0].matchedFaqId")).longValue())
                .isEqualTo(target);
        assertThat((String) read(recommendations, "$.data.recommendations[0].matchedFaqs[0].question")).isEqualTo(original);
        assertThat((Object) read(recommendations, "$.data.recommendations[0].matchedFaqs[0].matchedFaqSeqNum")).isNull();

        long before = faqCount();
        String finalQuestion = "확장 반영 질문 " + UUID.randomUUID();
        String applied = body(apply(analysisId, candidateId, finalQuestion, List.of("기존", "확장")).andExpect(status().isOk()));

        assertThat(((Number) read(applied, "$.data.appliedFaqId")).longValue()).isEqualTo(target);
        assertThat((String) read(applied, "$.data.editType")).isEqualTo("EXPAND");
        assertThat(faqCount()).isEqualTo(before);

        Map<String, Object> faq = faqRow(target);
        assertThat(faq.get("question")).isEqualTo(finalQuestion);
        assertThat(faq.get("answer")).isEqualTo("확장 반영 답변");
        assertThat(keywords(target)).containsExactly("등록", "기존", "확장");
        assertThat(((Number) faq.get("is_active")).intValue()).isEqualTo(1);

        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT edit_type, before_question, after_question FROM gold_faq_edit_history "
                        + "WHERE faq_id = ? ORDER BY history_id DESC LIMIT 1", target);
        assertThat(history.get("edit_type")).isEqualTo("EXPAND");
        assertThat(history.get("before_question")).isEqualTo(original);
        assertThat(history.get("after_question")).isEqualTo(finalQuestion);
        assertThat(candidateStatus(candidateId)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("예전 형식(원본 순번만)의 콜백도 저장되고 순번으로 확장 대상을 찾는다")
    void legacySeqOnlyCallbackStillWorks() throws Exception {
        int seq = randomSeq();
        long target = insertSourceFaq(seq, "원본 FAQ " + UUID.randomUUID());

        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("matchedFaqSeqNum", seq);
        legacy.put("matchScore", 0.88);
        Long analysisId = analysis(candidate("EXPAND", "예전 형식 확장", List.of(legacy)));
        Number candidateId = onlyCandidateId(analysisId);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT matched_faq_id, matched_faq_seq_num FROM gold_faq_candidate_match WHERE candidate_id = ?",
                candidateId.longValue());
        assertThat(stored.get("matched_faq_id")).isNull();
        assertThat(((Number) stored.get("matched_faq_seq_num")).intValue()).isEqualTo(seq);

        // 후보 목록은 순번으로 운영 FAQ ID를 찾아 보여 준다
        assertThat(((Number) read(recommendations(analysisId), "$.data.recommendations[0].matchedFaqs[0].matchedFaqId"))
                .longValue()).isEqualTo(target);

        String finalQuestion = "예전 형식 반영 " + UUID.randomUUID();
        String applied = body(apply(analysisId, candidateId, finalQuestion, List.of("확장")).andExpect(status().isOk()));
        assertThat(((Number) read(applied, "$.data.appliedFaqId")).longValue()).isEqualTo(target);
        assertThat(faqRow(target).get("question")).isEqualTo(finalQuestion);
    }

    @Test
    @DisplayName("매칭이 여러 개면 점수가 가장 높은 FAQ를 확장하고 나머지는 그대로 둔다")
    void expandsHighestScoredMatch() throws Exception {
        String lowQuestion = "낮은 점수 FAQ " + UUID.randomUUID();
        long low = createFaq(lowQuestion, List.of("낮음"));
        long high = createFaq("높은 점수 FAQ " + UUID.randomUUID(), List.of("높음"));
        long middle = insertSourceFaq(randomSeq(), "중간 점수 FAQ " + UUID.randomUUID());

        // 점수 순서와 다르게 보내도 점수로 고른다
        Long analysisId = analysis(candidate("EXPAND", "여러 매칭",
                List.of(match(low, null, 0.70), match(high, null, 0.95), match(middle, null, 0.80))));
        Number candidateId = onlyCandidateId(analysisId);

        String recommendations = recommendations(analysisId);
        List<Number> ids = read(recommendations, "$.data.recommendations[0].matchedFaqs[*].matchedFaqId");
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactly(high, middle, low);

        String finalQuestion = "최고점 반영 " + UUID.randomUUID();
        String applied = body(apply(analysisId, candidateId, finalQuestion, List.of("확장")).andExpect(status().isOk()));
        assertThat(((Number) read(applied, "$.data.appliedFaqId")).longValue()).isEqualTo(high);
        assertThat(faqRow(high).get("question")).isEqualTo(finalQuestion);
        assertThat(faqRow(low).get("question")).isEqualTo(lowQuestion);
    }

    @Test
    @DisplayName("ID와 원본 순번이 함께 오면 ID가 가리키는 FAQ를 확장한다")
    void faqIdTakesPrecedenceOverSeq() throws Exception {
        int seq = randomSeq();
        String sourceQuestion = "순번 FAQ " + UUID.randomUUID();
        long bySeq = insertSourceFaq(seq, sourceQuestion);
        long byId = createFaq("ID FAQ " + UUID.randomUUID(), List.of("아이디"));

        Long analysisId = analysis(candidate("EXPAND", "ID 우선", List.of(match(byId, seq, 0.90))));
        Number candidateId = onlyCandidateId(analysisId);
        assertThat(((Number) read(recommendations(analysisId), "$.data.recommendations[0].matchedFaqs[0].matchedFaqId"))
                .longValue()).isEqualTo(byId);

        String applied = body(apply(analysisId, candidateId, "ID 우선 반영 " + UUID.randomUUID(), List.of("확장"))
                .andExpect(status().isOk()));
        assertThat(((Number) read(applied, "$.data.appliedFaqId")).longValue()).isEqualTo(byId);
        assertThat(faqRow(bySeq).get("question")).isEqualTo(sourceQuestion);
    }

    @Test
    @DisplayName("대상 FAQ가 삭제됐으면 409, 원본 순번이 맞는 다른 FAQ로 대신 확장하지 않고 후보는 대기 상태로 남는다")
    void deletedTargetIsConflict() throws Exception {
        int seq = randomSeq();
        String sourceQuestion = "다른 활성 FAQ " + UUID.randomUUID();
        long other = insertSourceFaq(seq, sourceQuestion);
        String deletedQuestion = "삭제될 FAQ " + UUID.randomUUID();
        long deleted = createFaq(deletedQuestion, List.of("삭제"));
        mvc.perform(delete("/api/v1/faq/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());

        Long analysisId = analysis(candidate("EXPAND", "삭제된 대상", List.of(match(deleted, seq, 0.93))));
        Number candidateId = onlyCandidateId(analysisId);

        apply(analysisId, candidateId, "반영되면 안 됨", List.of("확장"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(candidateStatus(candidateId)).isEqualTo("PENDING");
        assertThat(faqRow(other).get("question")).isEqualTo(sourceQuestion);
        Map<String, Object> deletedRow = faqRow(deleted);
        assertThat(deletedRow.get("question")).isEqualTo(deletedQuestion);
        assertThat(((Number) deletedRow.get("is_active")).intValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("없는 FAQ ID가 와도 분석 결과는 저장되고, 승인하면 409")
    void unknownFaqIdIsStoredButConflictsOnApply() throws Exception {
        Long analysisId = analysis(candidate("EXPAND", "없는 대상", List.of(match(999_999_999L, null, 0.90))));
        Number candidateId = onlyCandidateId(analysisId);

        String recommendations = recommendations(analysisId);
        assertThat((Object) read(recommendations, "$.data.recommendations[0].matchedFaqs[0].matchedFaqId")).isNull();
        assertThat((Object) read(recommendations, "$.data.recommendations[0].matchedFaqs[0].question")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT matched_faq_id FROM gold_faq_candidate_match WHERE candidate_id = ?",
                Long.class, candidateId.longValue())).isEqualTo(999_999_999L);

        apply(analysisId, candidateId, "반영되면 안 됨", List.of("확장")).andExpect(status().isConflict());
        assertThat(candidateStatus(candidateId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("ID도 순번도 없는 매칭은 저장하지 않고, 대상이 없는 확장 후보는 409")
    void expandWithoutAnyTargetIsConflict() throws Exception {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("matchedFaqId", null);
        empty.put("matchedFaqSeqNum", null);
        empty.put("matchScore", 0.99);
        Long analysisId = analysis(candidate("EXPAND", "빈 매칭", List.of(empty)),
                candidate("EXPAND", "매칭 없음", List.of()));

        List<Number> candidateIds = read(recommendations(analysisId), "$.data.recommendations[*].candidateId");
        assertThat(candidateIds).hasSize(2);
        for (Number candidateId : candidateIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM gold_faq_candidate_match WHERE candidate_id = ?",
                    Integer.class, candidateId.longValue())).isZero();
            apply(analysisId, candidateId, "반영되면 안 됨", List.of("확장")).andExpect(status().isConflict());
            assertThat(candidateStatus(candidateId)).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("한 후보에 원본 순번 없는 매칭이 여러 개여도 모두 저장된다(후보+순번 유니크 제약과 충돌하지 않음)")
    void storesManyMatchesWithoutSeq() throws Exception {
        long first = createFaq("매칭1 " + UUID.randomUUID(), List.of("일"));
        long second = createFaq("매칭2 " + UUID.randomUUID(), List.of("이"));
        long third = createFaq("매칭3 " + UUID.randomUUID(), List.of("삼"));

        Long analysisId = analysis(candidate("EXPAND", "여러 매칭 저장",
                List.of(match(first, null, 0.91), match(second, null, 0.85), match(third, null, 0.80))));
        Number candidateId = onlyCandidateId(analysisId);

        assertThat(jdbcTemplate.queryForList(
                "SELECT matched_faq_id FROM gold_faq_candidate_match WHERE candidate_id = ? ORDER BY match_score DESC",
                Long.class, candidateId.longValue())).containsExactly(first, second, third);
        assertThat((List<?>) read(recommendations(analysisId), "$.data.recommendations[0].matchedFaqs")).hasSize(3);
    }

    @Test
    @DisplayName("신규(NEW) 후보는 매칭된 FAQ가 있어도 새 FAQ로 반영하고 매칭된 FAQ는 바꾸지 않는다")
    void newCandidateDoesNotTouchMatchedFaq() throws Exception {
        String matchedQuestion = "비슷한 FAQ " + UUID.randomUUID();
        long matched = createFaq(matchedQuestion, List.of("비슷"));

        Long analysisId = analysis(candidate("NEW", "신규 후보", List.of(match(matched, null, 0.60))));
        Number candidateId = onlyCandidateId(analysisId);

        long before = faqCount();
        String applied = body(apply(analysisId, candidateId, "신규 반영 " + UUID.randomUUID(), List.of("신규"))
                .andExpect(status().isOk()));
        assertThat(((Number) read(applied, "$.data.appliedFaqId")).longValue()).isNotEqualTo(matched);
        assertThat((String) read(applied, "$.data.editType")).isEqualTo("CREATE");
        assertThat(faqCount()).isEqualTo(before + 1);
        assertThat(faqRow(matched).get("question")).isEqualTo(matchedQuestion);
    }

    @Test
    @DisplayName("분석 결과 응답의 유사 FAQ에도 matchedFaqId가 나온다")
    void analysisResultIncludesMatchedFaqId() throws Exception {
        long target = createFaq("결과 응답 FAQ " + UUID.randomUUID(), List.of("결과"));
        int seq = randomSeq();
        Long analysisId = analysis(candidate("EXPAND", "결과 응답", List.of(match(target, null, 0.90), match(null, seq, 0.50))));

        String result = body(mvc.perform(get("/api/v1/dashboard/analyze/" + analysisId + "/result")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        assertThat(((Number) read(result, "$.data.faqCandidates[0].matchedFaqs[0].matchedFaqId")).longValue())
                .isEqualTo(target);
        assertThat((Object) read(result, "$.data.faqCandidates[0].matchedFaqs[0].matchedFaqSeqNum")).isNull();
        assertThat((Object) read(result, "$.data.faqCandidates[0].matchedFaqs[1].matchedFaqId")).isNull();
        assertThat(((Number) read(result, "$.data.faqCandidates[0].matchedFaqs[1].matchedFaqSeqNum")).intValue())
                .isEqualTo(seq);
    }

    @Test
    @DisplayName("한 번 확장 반영한 후보는 다시 반영할 수 없고 대상 FAQ도 다시 바뀌지 않는다")
    void appliedExpandCannotBeAppliedAgain() throws Exception {
        long target = createFaq("재반영 대상 " + UUID.randomUUID(), List.of("재반영"));
        Long analysisId = analysis(candidate("EXPAND", "재반영", List.of(match(target, null, 0.90))));
        Number candidateId = onlyCandidateId(analysisId);

        String firstQuestion = "첫 반영 " + UUID.randomUUID();
        apply(analysisId, candidateId, firstQuestion, List.of("확장")).andExpect(status().isOk());
        apply(analysisId, candidateId, "두 번째 반영", List.of("확장")).andExpect(status().isConflict());
        assertThat(faqRow(target).get("question")).isEqualTo(firstQuestion);
    }

    // ===== 도우미 =====

    @SafeVarargs
    private Long analysis(Map<String, Object>... candidates) throws Exception {
        String content = "번호,질문,날짜\n1,확장 문의 " + UUID.randomUUID() + ",2026-08-01\n";
        MockMultipartFile file = new MockMultipartFile("file", "expand.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        String response = body(mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        Long analysisId = ((Number) read(response, "$.data.analysisId")).longValue();

        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("clusterLabel", 3);
        cluster.put("name", "확장");
        cluster.put("topKeywords", List.of("확장"));
        cluster.put("size", 5);
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("status", "COMPLETED");
        callback.put("clusteringView", Map.of("clusterNames", List.of(cluster), "points", List.of()));
        callback.put("faqCandidates", List.of(candidates));

        mvc.perform(post("/api/v1/dashboard/analyze/callback/" + analysisId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(callback))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CALLBACK_TOKEN))
                .andExpect(status().isOk());
        return analysisId;
    }

    private Map<String, Object> candidate(String type, String question, List<Map<String, Object>> matches) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("clusterLabel", 3);
        candidate.put("candidateType", type);
        candidate.put("standardQuestion", question + " " + UUID.randomUUID());
        candidate.put("answerDraft", "안내 표지를 따라 이동하세요.");
        candidate.put("qType", Q_TYPE);
        candidate.put("category", "영업/제도");
        candidate.put("similarQuestions", List.of());
        candidate.put("occurrenceCount", 5);
        candidate.put("representativeKeywords", List.of("확장"));
        candidate.put("synonyms", List.of());
        candidate.put("matchedFaqs", new ArrayList<>(matches));
        return candidate;
    }

    private Map<String, Object> match(Long faqId, Integer seq, double score) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("matchedFaqId", faqId);
        match.put("matchedFaqSeqNum", seq);
        match.put("matchScore", score);
        return match;
    }

    private ResultActions apply(Long analysisId, Number candidateId, String question, List<String> keywords)
            throws Exception {
        Number clusterId = read(recommendations(analysisId),
                "$.data.recommendations[?(@.candidateId == " + candidateId + ")].clusterId", 0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("analysisId", analysisId);
        request.put("clusterId", clusterId);
        request.put("candidateId", candidateId);
        request.put("qType", Q_TYPE);
        request.put("finalQuestion", question);
        request.put("finalAnswer", "확장 반영 답변");
        request.put("keywords", keywords);
        request.put("isManualPatchConfirmed", true);
        return mvc.perform(post("/api/v1/faq/apply").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))
                .header(HttpHeaders.AUTHORIZATION, bearer(admin)));
    }

    private long createFaq(String question, List<String> keywords) throws Exception {
        Map<String, Object> request = Map.of("qType", Q_TYPE, "standardQuestion", question,
                "answer", "직접 등록 답변", "keywords", keywords);
        String response = body(mvc.perform(post("/api/v1/faq").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        return ((Number) read(response, "$.data.faqId")).longValue();
    }

    // 원본 FAQ에서 복사된 운영 FAQ(원본 순번 있음)를 흉내 낸다
    private long insertSourceFaq(int seq, String question) {
        jdbcTemplate.update("INSERT INTO gold_faq (question, answer, keywords, q_type, source_seq_num) VALUES (?, ?, ?, ?, ?)",
                question, "원본 답변", "[\"원본\"]", Q_TYPE, seq);
        return jdbcTemplate.queryForObject("SELECT faq_id FROM gold_faq WHERE source_seq_num = ?", Long.class, seq);
    }

    private int randomSeq() {
        return ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
    }

    private Number onlyCandidateId(Long analysisId) throws Exception {
        List<Number> ids = read(recommendations(analysisId), "$.data.recommendations[*].candidateId");
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private String recommendations(Long analysisId) throws Exception {
        return body(mvc.perform(get("/api/v1/faq/recommendations/" + analysisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
    }

    private String candidateStatus(Number candidateId) {
        return jdbcTemplate.queryForObject("SELECT review_status FROM gold_faq_candidate WHERE candidate_id = ?",
                String.class, candidateId.longValue());
    }

    private Map<String, Object> faqRow(long faqId) {
        return jdbcTemplate.queryForMap("SELECT question, answer, is_active FROM gold_faq WHERE faq_id = ?", faqId);
    }

    private List<String> keywords(long faqId) throws Exception {
        String json = jdbcTemplate.queryForObject("SELECT keywords FROM gold_faq WHERE faq_id = ?", String.class, faqId);
        return List.of(objectMapper.readValue(json, String[].class));
    }

    private long faqCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_faq", Long.class);
    }

    private String column(String name, String attribute) {
        return jdbcTemplate.queryForObject("SELECT " + attribute + " FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gold_faq_candidate_match' AND COLUMN_NAME = ?",
                String.class, name);
    }

    private <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    private <T> T read(String json, String path, int index) {
        List<T> values = JsonPath.read(json, path);
        return values.get(index);
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, Map<String, String> body)
            throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(ResultActions result) throws Exception {
        MvcResult mvcResult = result.andReturn();
        return mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
