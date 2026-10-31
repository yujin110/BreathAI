package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

// 키워드 다운로드: 현재 키워드 전체, 지정한 버전 이후 새로 생긴 키워드, 버전 생성 규칙
// 테스트 DB를 다른 시험과 함께 쓰므로 질문에는 무작위 꼬리를 붙이고, 비교는 이 시험에서 만든 FAQ로 한다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqKeywordExportIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";
    private static final String PATH = "/api/v1/faq/keywords/download";
    private static final int Q_TYPE = 176;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    private String adminEmail;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        adminEmail = newEmail("keyword-admin");
        admin = token(adminEmail, ADMIN_CODE);
    }

    @Test
    @DisplayName("전체: 지금 FAQ 키워드를 쉼표로 이어 쓰고, 키워드 없는 FAQ는 빈칸, 삭제된 FAQ와 유사어는 없다, 일반 계정도 받는다")
    void downloadsAllCurrentKeywords() throws Exception {
        String tail = tail();
        long withKeywords = createFaq("키워드 있음 " + tail, List.of("가", "나"), List.of("유사어는 안 나옴"));
        long withoutKeywords = createFaq("키워드 없음 " + tail, List.of(), null);
        long deleted = createFaq("삭제됨 " + tail, List.of("지움"), null);
        mvc.perform(delete("/api/v1/faq/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());

        MvcResult result = download(token(newEmail("keyword-user"), null), null);
        byte[] content = result.getResponse().getContentAsByteArray();
        assertThat(content[0]).isEqualTo((byte) 0xEF);
        assertThat(content[1]).isEqualTo((byte) 0xBB);
        assertThat(content[2]).isEqualTo((byte) 0xBF);
        String csv = text(result);
        assertThat(csv).startsWith("﻿ID,카테고리,질문,키워드\r\n");
        assertThat(csv).doesNotContain("유사어는 안 나옴");

        Map<Long, CSVRecord> rows = records(csv);
        assertThat(rows.get(withKeywords).get("키워드")).isEqualTo("가, 나");
        assertThat(rows.get(withKeywords).get("카테고리")).isEqualTo("영업/제도");
        assertThat(rows.get(withKeywords).get("질문")).isEqualTo("키워드 있음 " + tail);
        assertThat(rows.get(withoutKeywords).get("키워드")).isEmpty();
        assertThat(rows).doesNotContainKey(deleted);
        // 활성 FAQ 전체가 한 줄씩
        assertThat(rows).hasSize(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_faq WHERE is_active = 1", Integer.class));

        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(fileName(result)).matches("faq_keywords_v\\d+_" + today + "\\.csv");
    }

    @Test
    @DisplayName("버전: 바뀐 게 있으면 새 버전, 없으면 같은 버전이고 FAQ 다운로드와 버전 번호를 함께 쓴다")
    void sharesVersionRuleWithFaqDownload() throws Exception {
        String question = "버전 공유 " + tail();
        long faqId = createFaq(question, List.of("버전"), null);

        int first = version(download(admin, null));
        assertThat(version(download(admin, null))).isEqualTo(first);
        assertThat(faqDownloadVersion()).isEqualTo(first);

        updateFaq(faqId, question, List.of("버전", "추가"));
        int second = version(download(admin, null));
        assertThat(second).isGreaterThan(first);
        assertThat(faqDownloadVersion()).isEqualTo(second);

        // FAQ 다운로드가 먼저 새 버전을 만들어도 키워드 다운로드는 그 버전을 쓴다
        updateFaq(faqId, question, List.of("버전", "추가", "또"));
        int third = faqDownloadVersion();
        assertThat(third).isGreaterThan(second);
        assertThat(version(download(admin, null))).isEqualTo(third);
    }

    @Test
    @DisplayName("지정 버전 이후: 추가된 키워드만, 새 FAQ는 전체, 그대로·키워드 삭제·FAQ 삭제는 없음, 확장 반영으로 늘어난 키워드도 나온다")
    void downloadsKeywordsAddedSinceVersion() throws Exception {
        String tail = tail();
        String addedQuestion = "추가됨 " + tail;
        long added = createFaq(addedQuestion, List.of("a1", "a2"), null);
        long unchanged = createFaq("그대로 " + tail, List.of("b1"), null);
        String removedQuestion = "키워드 지움 " + tail;
        long removed = createFaq(removedQuestion, List.of("c1", "c2"), null);
        long deleted = createFaq("FAQ 삭제 " + tail, List.of("d1"), null);
        long expandTarget = createFaq("확장 대상 " + tail, List.of("e1"), null);
        int since = version(download(admin, null));

        updateFaq(added, addedQuestion, List.of("a1", "a3", "a2"));
        updateFaq(removed, removedQuestion, List.of("c1"));
        mvc.perform(delete("/api/v1/faq/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        long created = createFaq("새 FAQ " + tail, List.of("f1", "f2"), null);
        applyExpand(expandTarget, List.of("e1", "e2"));

        MvcResult result = download(admin, since);
        String csv = text(result);
        assertThat(csv).startsWith("﻿ID,카테고리,질문,새 키워드\r\n");
        Map<Long, CSVRecord> rows = records(csv);
        assertThat(rows.keySet()).containsExactlyInAnyOrder(added, created, expandTarget);
        assertThat(rows.get(added).get("새 키워드")).isEqualTo("a3");
        assertThat(rows.get(created).get("새 키워드")).isEqualTo("f1, f2");
        assertThat(rows.get(expandTarget).get("새 키워드")).isEqualTo("e2");
        assertThat(rows.get(added).get("카테고리")).isEqualTo("영업/제도");
        assertThat(rows).doesNotContainKeys(unchanged, removed, deleted);

        int latest = version(result);
        assertThat(latest).isGreaterThan(since);
        assertThat(fileName(result)).startsWith("faq_keywords_v" + since + "_to_v" + latest + "_");
    }

    @Test
    @DisplayName("지정한 버전이 최신이면 머리글만 있는 파일이다")
    void sinceLatestVersionHasHeaderOnly() throws Exception {
        createFaq("최신 기준 " + tail(), List.of("최신"), null);
        int latest = version(download(admin, null));

        MvcResult result = download(admin, latest);
        assertThat(text(result)).isEqualTo("﻿ID,카테고리,질문,새 키워드\r\n");
        assertThat(version(result)).isEqualTo(latest);
    }

    @Test
    @DisplayName("없는 버전이면 404이고 새 버전을 만들지 않는다, 토큰 없이는 401")
    void rejectsUnknownVersionAndAnonymous() throws Exception {
        createFaq("버전 미생성 확인 " + tail(), List.of("확인"), null);
        int before = versionCount();

        mvc.perform(get(PATH).param("sinceVersionId", "999999999").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(versionCount()).isEqualTo(before);

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        assertThat(versionCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("감사로그에 키워드 다운로드(EXPORT)가 기준 버전과 함께 남는다")
    void auditLogRecordsKeywordDownload() throws Exception {
        int latest = version(download(admin, null));
        download(admin, latest);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, category, query, status FROM gold_audit_log WHERE email = ? AND path = ? ORDER BY log_id",
                adminEmail, PATH);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("action")).isEqualTo("키워드 다운로드");
            assertThat(r.get("category")).isEqualTo("EXPORT");
            assertThat(((Number) r.get("status")).intValue()).isEqualTo(200);
        });
        assertThat(rows.get(1).get("query")).isEqualTo("sinceVersionId=" + latest);
    }

    // ===== 도우미 =====

    // 실제 요청처럼 주소에 쿼리를 붙인다 (.param()은 감사로그의 쿼리 문자열에 남지 않는다)
    private MvcResult download(String token, Integer sinceVersionId) throws Exception {
        String url = sinceVersionId != null ? PATH + "?sinceVersionId=" + sinceVersionId : PATH;
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk()).andReturn();
    }

    private int faqDownloadVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/faq/download").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        Matcher matcher = Pattern.compile("faq_v(\\d+)_").matcher(fileName(result));
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    // 키워드 파일 이름의 마지막 버전 번호 (faq_keywords_v5_… 또는 faq_keywords_v3_to_v5_…)
    private int version(MvcResult result) {
        Matcher matcher = Pattern.compile("v(\\d+)_\\d{8}\\.csv$").matcher(fileName(result));
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String fileName(MvcResult result) {
        Matcher matcher = Pattern.compile("filename=\"([^\"]+)\"")
                .matcher(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private Map<Long, CSVRecord> records(String csv) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        Map<Long, CSVRecord> rows = new LinkedHashMap<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(csv.substring(1)), format)) {
            for (CSVRecord record : parser) {
                rows.put(Long.parseLong(record.get("ID")), record);
            }
        }
        return rows;
    }

    private String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long createFaq(String question, List<String> keywords, List<String> synonyms) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("qType", Q_TYPE);
        request.put("standardQuestion", question);
        request.put("answer", "키워드 답변");
        request.put("keywords", keywords);
        if (synonyms != null) request.put("synonyms", synonyms);
        String response = body(mvc.perform(post("/api/v1/faq").contentType(MediaType.APPLICATION_JSON)
                .content(json(request)).header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        return ((Number) read(response, "$.data.faqId")).longValue();
    }

    private void updateFaq(long faqId, String question, List<String> keywords) throws Exception {
        mvc.perform(patch("/api/v1/faq/" + faqId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("qType", Q_TYPE, "standardQuestion", question, "answer", "키워드 답변",
                                "keywords", keywords, "editReason", "키워드 시험")))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
    }

    // 확장 후보를 만들어 target FAQ에 반영한다 (키워드는 기존 것에 합쳐진다)
    private void applyExpand(long target, List<String> keywords) throws Exception {
        String content = "번호,질문,날짜\n1,키워드 문의 " + UUID.randomUUID() + ",2026-08-01\n";
        MockMultipartFile file = new MockMultipartFile("file", "keyword.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        Long analysisId = ((Number) read(body(mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk())), "$.data.analysisId")).longValue();

        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("clusterLabel", 3);
        candidate.put("candidateType", "EXPAND");
        candidate.put("standardQuestion", "확장 후보 " + UUID.randomUUID());
        candidate.put("answerDraft", "확장 답변");
        candidate.put("qType", Q_TYPE);
        candidate.put("category", "영업/제도");
        candidate.put("similarQuestions", List.of());
        candidate.put("occurrenceCount", 5);
        candidate.put("representativeKeywords", keywords);
        candidate.put("synonyms", List.of());
        candidate.put("matchedFaqs", List.of(Map.of("matchedFaqId", target, "matchScore", 0.9)));
        Map<String, Object> cluster = Map.of("clusterLabel", 3, "name", "키워드", "topKeywords", List.of("키워드"), "size", 5);
        mvc.perform(post("/api/v1/dashboard/analyze/callback/" + analysisId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "COMPLETED",
                                "clusteringView", Map.of("clusterNames", List.of(cluster), "points", List.of()),
                                "faqCandidates", List.of(candidate))))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + CALLBACK_TOKEN))
                .andExpect(status().isOk());

        String recommendations = body(mvc.perform(get("/api/v1/faq/recommendations/" + analysisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        Map<String, Object> apply = new HashMap<>();
        apply.put("analysisId", analysisId);
        apply.put("clusterId", read(recommendations, "$.data.recommendations[0].clusterId"));
        apply.put("candidateId", read(recommendations, "$.data.recommendations[0].candidateId"));
        apply.put("qType", Q_TYPE);
        apply.put("finalQuestion", "확장 반영 " + UUID.randomUUID());
        apply.put("finalAnswer", "확장 반영 답변");
        apply.put("keywords", keywords);
        apply.put("isManualPatchConfirmed", true);
        String applied = body(mvc.perform(post("/api/v1/faq/apply").contentType(MediaType.APPLICATION_JSON)
                        .content(json(apply)).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        assertThat(((Number) read(applied, "$.data.appliedFaqId")).longValue()).isEqualTo(target);
    }

    private int versionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_faq_version", Integer.class);
    }

    private String token(String email, String adminCode) throws Exception {
        TestSignup.signup(mvc, emailService, email, PASSWORD, adminCode);
        return read(body(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", PASSWORD)))).andExpect(status().isOk())),
                "$.data.accessToken");
    }

    private String tail() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String newEmail(String prefix) {
        return prefix + "-" + tail() + "@seoulmetro.co.kr";
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
