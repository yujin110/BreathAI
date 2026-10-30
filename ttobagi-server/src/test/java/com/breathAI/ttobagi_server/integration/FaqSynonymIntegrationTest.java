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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FAQ 유사어: 등록·수정·삭제 이력, 후보 수정·반영, 버전 사본·비교·복원, CSV 다운로드
// 테스트 DB를 다른 시험과 함께 쓰므로 질문에는 무작위 꼬리를 붙인다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqSynonymIntegrationTest {

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
        String email = "synonym-" + tail() + "@seoulmetro.co.kr";
        TestSignup.signup(mvc, emailService, email, PASSWORD, ADMIN_CODE);
        admin = read(body(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", PASSWORD)))).andExpect(status().isOk())),
                "$.data.accessToken");
    }

    @Test
    @DisplayName("V12: FAQ·버전 사본·수정 이력에 유사어 칸이 생긴다")
    void migrationAddsSynonymColumns() {
        assertThat(columnExists("gold_faq", "synonyms")).isTrue();
        assertThat(columnExists("gold_faq_version_item", "synonyms")).isTrue();
        assertThat(columnExists("gold_faq_edit_history", "before_synonyms")).isTrue();
        assertThat(columnExists("gold_faq_edit_history", "after_synonyms")).isTrue();
    }

    // ===== 등록·수정·삭제 =====

    @Test
    @DisplayName("등록: 유사어를 정리해 저장하고(공백·빈 값·대소문자 중복 제거) 상세·목록·이력에 나온다")
    void createStoresNormalizedSynonyms() throws Exception {
        String question = "유사어 등록 " + tail();
        List<String> raw = new ArrayList<>(List.of("  분실물센터 ", "유실물센타", "", "   ", "분실물센터", "LOST", "lost"));
        raw.add(null);
        long faqId = createFaq(question, List.of("분실물"), raw);

        List<String> expected = List.of("분실물센터", "유실물센타", "LOST");
        assertThat(detailSynonyms(faqId)).isEqualTo(expected);
        assertThat(listSynonyms(question)).isEqualTo(expected);
        Map<String, Object> history = latestHistory(faqId);
        assertThat(history.get("editType")).isEqualTo("CREATE");
        assertThat(history.get("afterSynonyms")).isEqualTo(expected);
        // 키워드는 지금처럼 그대로 저장한다
        assertThat(this.<List<String>>read(detail(faqId), "$.data.keywords")).containsExactly("분실물");
    }

    @Test
    @DisplayName("등록할 때 유사어를 보내지 않으면 빈 목록이다")
    void createWithoutSynonyms() throws Exception {
        long faqId = createFaq("유사어 없음 " + tail(), List.of("키워드"), null);
        assertThat(detailSynonyms(faqId)).isEmpty();
        assertThat(latestHistory(faqId).get("afterSynonyms")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("수정: 유사어를 생략하면 그대로, 다른 목록이면 교체, []면 비우고 이력에 전후 값이 남는다")
    void updateSynonyms() throws Exception {
        String question = "유사어 수정 " + tail();
        long faqId = createFaq(question, List.of("키워드"), List.of("하나", "둘"));

        // 생략: 그대로
        String omitted = body(updateFaq(faqId, question, null).andExpect(status().isOk()));
        assertThat(this.<List<String>>read(omitted, "$.data.synonyms")).containsExactly("하나", "둘");
        assertThat(detailSynonyms(faqId)).containsExactly("하나", "둘");
        Map<String, Object> keep = latestHistory(faqId);
        assertThat(keep.get("beforeSynonyms")).isEqualTo(List.of("하나", "둘"));
        assertThat(keep.get("afterSynonyms")).isEqualTo(List.of("하나", "둘"));

        // 교체
        String replaced = body(updateFaq(faqId, question, List.of(" 셋 ", "넷", "셋")).andExpect(status().isOk()));
        assertThat(this.<List<String>>read(replaced, "$.data.synonyms")).containsExactly("셋", "넷");
        Map<String, Object> change = latestHistory(faqId);
        assertThat(change.get("editType")).isEqualTo("MANUAL");
        assertThat(change.get("beforeSynonyms")).isEqualTo(List.of("하나", "둘"));
        assertThat(change.get("afterSynonyms")).isEqualTo(List.of("셋", "넷"));

        // 비우기
        updateFaq(faqId, question, List.of()).andExpect(status().isOk());
        assertThat(detailSynonyms(faqId)).isEmpty();
        assertThat(latestHistory(faqId).get("afterSynonyms")).isEqualTo(List.of());
        // 키워드는 영향 없음
        assertThat(this.<List<String>>read(detail(faqId), "$.data.keywords")).containsExactly("키워드");
    }

    @Test
    @DisplayName("삭제 이력에 삭제 전 유사어가 남는다")
    void deleteHistoryKeepsSynonyms() throws Exception {
        long faqId = createFaq("유사어 삭제 " + tail(), List.of("키워드"), List.of("지울것"));
        mvc.perform(delete("/api/v1/faq/" + faqId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        Map<String, Object> history = latestHistory(faqId);
        assertThat(history.get("editType")).isEqualTo("DELETE");
        assertThat(history.get("beforeSynonyms")).isEqualTo(List.of("지울것"));
    }

    // ===== 후보 =====

    @Test
    @DisplayName("후보 수정: 유사어를 교체하고 기존 글자의 종류는 유지, 새 글자는 종류 없음, 생략하면 그대로, 검토 기록에 전후 값")
    void candidateUpdateReplacesSynonyms() throws Exception {
        Long analysisId = analysis(candidate("NEW", List.of(
                Map.of("text", "분실물센타", "type", "TYPO"), Map.of("text", "LF", "type", "ABBR")), List.of()));
        Number candidateId = onlyCandidateId(analysisId);

        String updated = body(updateCandidate(candidateId, List.of("분실물센타", " 유실물 보관소 ", "유실물 보관소"))
                .andExpect(status().isOk()));
        assertThat(this.<List<String>>read(updated, "$.data.synonyms[*].text")).containsExactly("분실물센타", "유실물 보관소");
        assertThat((String) read(updated, "$.data.synonyms[0].type")).isEqualTo("TYPO");
        assertThat((Object) read(updated, "$.data.synonyms[1].type")).isNull();

        // 생략하면 그대로
        String kept = body(updateCandidate(candidateId, null).andExpect(status().isOk()));
        assertThat(this.<List<String>>read(kept, "$.data.synonyms[*].text")).containsExactly("분실물센타", "유실물 보관소");

        // 검토 기록에는 바뀐 칸으로 유사어 전후 값이 남는다
        String note = jdbcTemplate.queryForObject("SELECT note FROM gold_faq_action_log WHERE candidate_id = ? "
                + "AND action = 'EDIT' ORDER BY log_id LIMIT 1", String.class, candidateId.longValue());
        Map<String, Map<String, Object>> parsed = objectMapper.readValue(note, Map.class);
        assertThat(parsed.get("before").get("synonyms")).isEqualTo(List.of("분실물센타", "LF"));
        assertThat(parsed.get("after").get("synonyms")).isEqualTo(List.of("분실물센타", "유실물 보관소"));

        // []면 비운다
        String cleared = body(updateCandidate(candidateId, List.of()).andExpect(status().isOk()));
        assertThat((List<?>) read(cleared, "$.data.synonyms")).isEmpty();
    }

    @Test
    @DisplayName("후보 수정: 100자를 넘는 유사어는 400이고 바뀌지 않는다, 대소문자만 다른 유사어는 하나만 남는다")
    void candidateUpdateValidatesSynonyms() throws Exception {
        Long analysisId = analysis(candidate("NEW", List.of(Map.of("text", "원래", "type", "SYNONYM")), List.of()));
        Number candidateId = onlyCandidateId(analysisId);

        updateCandidate(candidateId, List.of("가".repeat(101))).andExpect(status().isBadRequest());
        assertThat(this.<List<String>>read(recommendations(analysisId), "$.data.recommendations[0].synonyms[*].text"))
                .containsExactly("원래");

        String updated = body(updateCandidate(candidateId, List.of("가".repeat(100), "Metro", "METRO", "metro"))
                .andExpect(status().isOk()));
        assertThat(this.<List<String>>read(updated, "$.data.synonyms[*].text")).containsExactly("가".repeat(100), "Metro");
    }

    @Test
    @DisplayName("신규 반영: 보낸 유사어를 저장하고, 생략하면 후보의 유사어, []면 없음")
    void applyNewCandidateSynonyms() throws Exception {
        List<Map<String, Object>> candidateSynonyms = List.of(
                Map.of("text", "후보유사어1", "type", "TYPO"), Map.of("text", "후보유사어2", "type", "SYNONYM"));

        Long sent = analysis(candidate("NEW", candidateSynonyms, List.of()));
        long sentFaq = appliedFaqId(apply(sent, onlyCandidateId(sent), List.of("직접 고른 유사어")).andExpect(status().isOk()));
        assertThat(detailSynonyms(sentFaq)).containsExactly("직접 고른 유사어");
        assertThat(latestHistory(sentFaq).get("afterSynonyms")).isEqualTo(List.of("직접 고른 유사어"));

        Long omitted = analysis(candidate("NEW", candidateSynonyms, List.of()));
        long omittedFaq = appliedFaqId(apply(omitted, onlyCandidateId(omitted), null).andExpect(status().isOk()));
        assertThat(detailSynonyms(omittedFaq)).containsExactly("후보유사어1", "후보유사어2");

        Long empty = analysis(candidate("NEW", candidateSynonyms, List.of()));
        long emptyFaq = appliedFaqId(apply(empty, onlyCandidateId(empty), List.of()).andExpect(status().isOk()));
        assertThat(detailSynonyms(emptyFaq)).isEmpty();
    }

    @Test
    @DisplayName("확장 반영: 기존 FAQ의 유사어에 합치고(중복 제거·순서 유지) 이력에 전후 값, 생략하면 후보의 유사어를 합친다")
    void applyExpandMergesSynonyms() throws Exception {
        long target = createFaq("확장 대상 " + tail(), List.of("키워드"), List.of("기존", "공통"));
        Long analysisId = analysis(candidate("EXPAND", List.of(Map.of("text", "후보것", "type", "TYPO")),
                List.of(Map.of("matchedFaqId", target, "matchScore", 0.9))));

        long applied = appliedFaqId(apply(analysisId, onlyCandidateId(analysisId), List.of("공통", "새것", "기존"))
                .andExpect(status().isOk()));
        assertThat(applied).isEqualTo(target);
        assertThat(detailSynonyms(target)).containsExactly("기존", "공통", "새것");
        Map<String, Object> history = latestHistory(target);
        assertThat(history.get("editType")).isEqualTo("EXPAND");
        assertThat(history.get("beforeSynonyms")).isEqualTo(List.of("기존", "공통"));
        assertThat(history.get("afterSynonyms")).isEqualTo(List.of("기존", "공통", "새것"));

        Long second = analysis(candidate("EXPAND", List.of(Map.of("text", "후보것", "type", "TYPO")),
                List.of(Map.of("matchedFaqId", target, "matchScore", 0.9))));
        apply(second, onlyCandidateId(second), null).andExpect(status().isOk());
        assertThat(detailSynonyms(target)).containsExactly("기존", "공통", "새것", "후보것");
    }

    // ===== 버전 =====

    @Test
    @DisplayName("버전: 사본에 유사어가 남고, 비교에 synonyms 변경이 나오고, 복원하면 유사어가 돌아간다")
    void versionSnapshotCompareAndRestore() throws Exception {
        String question = "버전 유사어 " + tail();
        long faqId = createFaq(question, List.of("키워드"), List.of("버전전"));
        Number v1 = createVersion();

        updateFaq(faqId, question, List.of("버전후", "추가")).andExpect(status().isOk());

        // 그 버전 시점의 목록에는 예전 유사어가 나온다
        String versionList = body(mvc.perform(get("/api/v1/faq").param("versionId", v1.toString())
                        .param("keyword", question).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        assertThat(this.<List<String>>read(versionList, "$.data.faqList[0].synonyms")).containsExactly("버전전");

        // 비교: 유사어만 바뀐 것으로 나온다
        String compare = body(mvc.perform(get("/api/v1/faq/versions/compare").param("baseId", v1.toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        List<Map<String, Object>> changes = read(compare, "$.data.changed[?(@.faqId == " + faqId + ")].changes[*]");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("field")).isEqualTo("synonyms");
        assertThat(changes.get(0).get("before")).isEqualTo(List.of("버전전"));
        assertThat(changes.get(0).get("after")).isEqualTo(List.of("버전후", "추가"));

        // 복원
        String restored = body(mvc.perform(post("/api/v1/faq/versions/" + v1 + "/restore")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        assertThat((Integer) read(restored, "$.data.updatedCount")).isEqualTo(1);
        assertThat(detailSynonyms(faqId)).containsExactly("버전전");
        Map<String, Object> history = latestHistory(faqId);
        assertThat(history.get("editType")).isEqualTo("MANUAL");
        assertThat(history.get("beforeSynonyms")).isEqualTo(List.of("버전후", "추가"));
        assertThat(history.get("afterSynonyms")).isEqualTo(List.of("버전전"));
    }

    @Test
    @DisplayName("버전 사본의 유사어가 NULL(V12 이전)이고 지금이 빈 목록이면 바뀐 것으로 보지 않는다")
    void nullAndEmptySynonymsAreSame() throws Exception {
        long faqId = createFaq("빈 유사어 " + tail(), List.of("키워드"), List.of());
        assertThat(jdbcTemplate.queryForObject("SELECT synonyms FROM gold_faq WHERE faq_id = ?", String.class, faqId))
                .isEqualTo("[]");
        Number version = createVersion();
        jdbcTemplate.update("UPDATE gold_faq_version_item SET synonyms = NULL WHERE version_id = ? AND faq_id = ?",
                version.longValue(), faqId);

        String compare = body(mvc.perform(get("/api/v1/faq/versions/compare").param("baseId", version.toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        assertThat((List<?>) read(compare, "$.data.changed[?(@.faqId == " + faqId + ")]")).isEmpty();

        mvc.perform(post("/api/v1/faq/versions/" + version + "/restore").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.newVersionId").doesNotExist());
    }

    // ===== CSV =====

    @Test
    @DisplayName("CSV: 유사어 열이 쉼표로 이어 써지고, 유사어만 고쳐도 최신 다운로드가 새 버전을 만든다")
    void csvIncludesSynonymsAndSynonymOnlyEditMakesVersion() throws Exception {
        String question = "CSV 유사어 " + tail();
        long faqId = createFaq(question, List.of("키1", "키2"), List.of("유1", "유2"));

        MvcResult first = download(null);
        int firstVersion = versionNumber(first);
        String line = text(first).lines().filter(l -> l.contains(question)).findFirst().orElseThrow();
        assertThat(line).endsWith(",\"키1, 키2\",\"유1, 유2\"");

        // 바뀐 게 없으면 같은 버전
        assertThat(versionNumber(download(null))).isEqualTo(firstVersion);

        // 유사어만 바꾸면 새 버전
        updateFaq(faqId, question, List.of("유3")).andExpect(status().isOk());
        MvcResult second = download(null);
        assertThat(versionNumber(second)).isGreaterThan(firstVersion);
        String changed = text(second).lines().filter(l -> l.contains(question)).findFirst().orElseThrow();
        assertThat(changed).endsWith(",\"키1, 키2\",유3");

        // 지난 버전 파일에는 그때 유사어가 남는다
        String old = text(download(firstVersion)).lines().filter(l -> l.contains(question)).findFirst().orElseThrow();
        assertThat(old).endsWith(",\"유1, 유2\"");
    }

    // ===== 도우미 =====

    private long createFaq(String question, List<String> keywords, List<String> synonyms) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("qType", Q_TYPE);
        request.put("standardQuestion", question);
        request.put("answer", "유사어 답변");
        request.put("keywords", keywords);
        if (synonyms != null) request.put("synonyms", synonyms);
        String response = body(mvc.perform(post("/api/v1/faq").contentType(MediaType.APPLICATION_JSON)
                .content(json(request)).header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        return ((Number) read(response, "$.data.faqId")).longValue();
    }

    private ResultActions updateFaq(long faqId, String question, List<String> synonyms) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("qType", Q_TYPE);
        request.put("standardQuestion", question);
        request.put("answer", "유사어 답변");
        request.put("editReason", "유사어 시험");
        if (synonyms != null) request.put("synonyms", synonyms);
        return mvc.perform(patch("/api/v1/faq/" + faqId).contentType(MediaType.APPLICATION_JSON)
                .content(json(request)).header(HttpHeaders.AUTHORIZATION, bearer(admin)));
    }

    private ResultActions updateCandidate(Number candidateId, List<String> synonyms) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("standardQuestion", "후보 질문");
        request.put("answerDraft", "후보 답변");
        if (synonyms != null) request.put("synonyms", synonyms);
        return mvc.perform(patch("/api/v1/faq/recommendations/" + candidateId).contentType(MediaType.APPLICATION_JSON)
                .content(json(request)).header(HttpHeaders.AUTHORIZATION, bearer(admin)));
    }

    private ResultActions apply(Long analysisId, Number candidateId, List<String> synonyms) throws Exception {
        Number clusterId = read(recommendations(analysisId), "$.data.recommendations[0].clusterId");
        Map<String, Object> request = new HashMap<>();
        request.put("analysisId", analysisId);
        request.put("clusterId", clusterId);
        request.put("candidateId", candidateId);
        request.put("qType", Q_TYPE);
        request.put("finalQuestion", "반영 질문 " + tail());
        request.put("finalAnswer", "반영 답변");
        request.put("keywords", List.of("반영"));
        request.put("isManualPatchConfirmed", true);
        if (synonyms != null) request.put("synonyms", synonyms);
        return mvc.perform(post("/api/v1/faq/apply").contentType(MediaType.APPLICATION_JSON)
                .content(json(request)).header(HttpHeaders.AUTHORIZATION, bearer(admin)));
    }

    private long appliedFaqId(ResultActions result) throws Exception {
        return ((Number) read(body(result), "$.data.appliedFaqId")).longValue();
    }

    private Long analysis(Map<String, Object> candidate) throws Exception {
        String content = "번호,질문,날짜\n1,유사어 문의 " + UUID.randomUUID() + ",2026-08-01\n";
        MockMultipartFile file = new MockMultipartFile("file", "synonym.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        Long analysisId = ((Number) read(body(mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk())), "$.data.analysisId")).longValue();

        Map<String, Object> cluster = Map.of("clusterLabel", 3, "name", "유사어", "topKeywords", List.of("유사어"), "size", 5);
        Map<String, Object> callback = Map.of("status", "COMPLETED",
                "clusteringView", Map.of("clusterNames", List.of(cluster), "points", List.of()),
                "faqCandidates", List.of(candidate));
        mvc.perform(post("/api/v1/dashboard/analyze/callback/" + analysisId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(callback)).header(HttpHeaders.AUTHORIZATION, "Bearer " + CALLBACK_TOKEN))
                .andExpect(status().isOk());
        return analysisId;
    }

    private Map<String, Object> candidate(String type, List<?> synonyms, List<?> matches) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("clusterLabel", 3);
        candidate.put("candidateType", type);
        candidate.put("standardQuestion", "유사어 후보 " + UUID.randomUUID());
        candidate.put("answerDraft", "후보 답변 초안");
        candidate.put("qType", Q_TYPE);
        candidate.put("category", "영업/제도");
        candidate.put("similarQuestions", List.of());
        candidate.put("occurrenceCount", 5);
        candidate.put("representativeKeywords", List.of("유사어"));
        candidate.put("synonyms", synonyms);
        candidate.put("matchedFaqs", matches);
        return candidate;
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

    private Number createVersion() throws Exception {
        return read(body(mvc.perform(post("/api/v1/faq/versions").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())), "$.data.versionId");
    }

    private MvcResult download(Integer versionId) throws Exception {
        return mvc.perform(get("/api/v1/faq/download" + (versionId != null ? "?versionId=" + versionId : ""))
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()).andReturn();
    }

    // 파일 이름 faq_v{버전번호}_{날짜}.csv 에서 버전 번호
    private int versionNumber(MvcResult result) {
        Matcher matcher = Pattern.compile("faq_v(\\d+)_").matcher(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String detail(long faqId) throws Exception {
        return body(mvc.perform(get("/api/v1/faq/" + faqId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
    }

    private List<String> detailSynonyms(long faqId) throws Exception {
        return read(detail(faqId), "$.data.synonyms");
    }

    private List<String> listSynonyms(String question) throws Exception {
        String list = body(mvc.perform(get("/api/v1/faq").param("keyword", question)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        List<List<String>> synonyms = read(list, "$.data.faqList[*].synonyms");
        assertThat(synonyms).hasSize(1);
        return synonyms.get(0);
    }

    private Map<String, Object> latestHistory(long faqId) throws Exception {
        return read(body(mvc.perform(get("/api/v1/faq/history").param("faqId", String.valueOf(faqId)).param("size", "1")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk())), "$.data.histories[0]");
    }

    private boolean columnExists(String table, String column) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?", Integer.class, table, column) == 1;
    }

    private String tail() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
