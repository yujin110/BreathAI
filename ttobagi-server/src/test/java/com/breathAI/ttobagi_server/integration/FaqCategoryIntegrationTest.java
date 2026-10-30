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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FAQ 카테고리 생성과, 기존 카테고리(bronze_cate_info)와 합쳐 읽기 확인
// 테스트 DB의 기존 카테고리: 172 승차권/운임, 176 영업/제도, 188 열차운영관련 (db/testdata/V900)
// 테스트 DB를 다른 시험과 함께 쓰므로 이름에는 무작위 꼬리를 붙인다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqCategoryIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String CALLBACK_TOKEN = "test-callback-token";
    private static final String PASSWORD = "qatest1!";
    private static final String PATH = "/api/v1/faq/categories";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    private String adminEmail;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        adminEmail = newEmail("category-admin");
        admin = token(adminEmail, ADMIN_CODE);
    }

    // ===== 생성 =====

    @Test
    @DisplayName("V11: gold_faq_category가 생기고 이름 중복 확인용 칸에 유니크 제약이 있다")
    void migrationCreatesCategoryTable() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gold_faq_category' "
                + "AND INDEX_NAME = 'uq_faq_category_name_key' AND NON_UNIQUE = 0", Integer.class)).isEqualTo(1);
        // 합쳐 읽는 bronze_cate_info와 정렬 규칙이 같아야 한다
        assertThat(collation("gold_faq_category", "name")).isEqualTo(collation("bronze_cate_info", "q_disp_name"));
    }

    @Test
    @DisplayName("카테고리를 만들면 공백을 정리해 저장하고 100000 이상 코드를 준다, 만든 사람도 남긴다")
    void createsCategory() throws Exception {
        String tail = tail();
        String response = body(create(admin, "  역사   시설 " + tail + "  ").andExpect(status().isOk()));

        int qType = read(response, "$.data.qType");
        assertThat(qType).isGreaterThanOrEqualTo(100000);
        assertThat((String) read(response, "$.data.name")).isEqualTo("역사 시설 " + tail);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT c.name, c.name_key, u.email FROM gold_faq_category c "
                        + "LEFT JOIN gold_user u ON u.user_id = c.created_by WHERE c.q_type = ?", qType);
        assertThat(row.get("name")).isEqualTo("역사 시설 " + tail);
        assertThat(row.get("name_key")).isEqualTo("역사시설" + tail);
        assertThat(row.get("email")).isEqualTo(adminEmail);
    }

    @Test
    @DisplayName("연달아 만들면 코드가 1씩 늘어난다")
    void codesIncreaseByOne() throws Exception {
        int first = read(body(create(admin, "첫째 " + tail()).andExpect(status().isOk())), "$.data.qType");
        int second = read(body(create(admin, "둘째 " + tail()).andExpect(status().isOk())), "$.data.qType");
        int third = read(body(create(admin, "셋째 " + tail()).andExpect(status().isOk())), "$.data.qType");
        assertThat(second).isEqualTo(first + 1);
        assertThat(third).isEqualTo(second + 1);
    }

    @Test
    @DisplayName("기존 카테고리에 100000 이상 코드가 생겨도 그보다 큰 코드를 쓴다")
    void nextCodeSkipsBronzeCodes() throws Exception {
        int bronzeCode = 500000 + (int) (Math.random() * 100000);
        String bronzeName = "AI 적재 카테고리 " + tail();
        jdbcTemplate.update("INSERT INTO bronze_cate_info (seq_num, q_type, q_disp_name, state) VALUES (?, ?, ?, 1)",
                bronzeCode, bronzeCode, bronzeName);
        try {
            int qType = read(body(create(admin, "큰 코드 다음 " + tail()).andExpect(status().isOk())), "$.data.qType");
            assertThat(qType).isEqualTo(bronzeCode + 1);
            // 새로 적재된 기존 카테고리 이름과도 중복을 확인한다
            create(admin, bronzeName.replace(" ", "")).andExpect(status().isConflict());
        } finally {
            jdbcTemplate.update("DELETE FROM bronze_cate_info WHERE seq_num = ?", bronzeCode);
        }
    }

    // ===== 목록 =====

    @Test
    @DisplayName("카테고리 목록에 기존 카테고리와 새 카테고리가 함께 코드순으로 나온다")
    void listContainsBronzeAndNewCategories() throws Exception {
        String name = "목록 확인 " + tail();
        int qType = read(body(create(admin, name).andExpect(status().isOk())), "$.data.qType");

        String list = body(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(token(newEmail("viewer"), null))))
                .andExpect(status().isOk()));
        List<Integer> codes = read(list, "$.data.categories[*].qType");
        assertThat(codes).isSorted();
        assertThat(categoryName(list, 172)).isEqualTo("승차권/운임");
        assertThat(categoryName(list, 176)).isEqualTo("영업/제도");
        assertThat(categoryName(list, 188)).isEqualTo("열차운영관련");
        assertThat(categoryName(list, qType)).isEqualTo(name);
        assertThat(codes).doesNotHaveDuplicates();
    }

    // ===== 중복 =====

    @Test
    @DisplayName("공백을 빼고 같은 이름은 409이고 아무것도 저장되지 않는다")
    void rejectsDuplicateNames() throws Exception {
        String tail = tail();
        create(admin, "중복 검사 " + tail).andExpect(status().isOk());
        int before = categoryCount();

        for (String name : List.of("중복 검사 " + tail, "중복검사" + tail, "  중복   검사 " + tail + " ",
                "중 복 검 사 " + tail, "중복\t검사\n" + tail, "중복　검사" + tail)) {
            create(admin, name)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("이미 있는 카테고리입니다."));
        }
        assertThat(categoryCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("기존 카테고리(bronze)와 공백을 빼고 같은 이름도 409")
    void rejectsBronzeCategoryNames() throws Exception {
        int before = categoryCount();
        for (String name : List.of("영업/제도", "영업 / 제도", " 승차권/운임 ", "열차 운영 관련", "열차운영관련")) {
            create(admin, name).andExpect(status().isConflict());
        }
        assertThat(categoryCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("기존 카테고리 이름의 일부만 같은 이름은 만들 수 있다")
    void allowsPartialOverlapWithBronzeName() throws Exception {
        create(admin, "열차운영 " + tail()).andExpect(status().isOk());
        create(admin, "영업 " + tail()).andExpect(status().isOk());
    }

    // ===== 입력 오류·권한 =====

    @Test
    @DisplayName("이름이 비었거나 공백뿐이거나 100자를 넘으면 400, 100자는 된다")
    void validatesName() throws Exception {
        int before = categoryCount();
        for (String name : List.of("", "   ", "\t\n", "　　", "가".repeat(101))) {
            create(admin, name).andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":null}")
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isBadRequest());
        assertThat(categoryCount()).isEqualTo(before);

        String hundred = tail() + "가".repeat(100 - tail().length());
        assertThat(hundred).hasSize(100);
        create(admin, hundred).andExpect(status().isOk());
    }

    @Test
    @DisplayName("일반 계정은 403, 토큰 없이는 401이고 아무것도 저장되지 않는다")
    void requiresAdmin() throws Exception {
        int before = categoryCount();
        create(token(newEmail("category-user"), null), "권한 없음 " + tail()).andExpect(status().isForbidden());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "토큰 없음 " + tail()))))
                .andExpect(status().isUnauthorized());
        assertThat(categoryCount()).isEqualTo(before);
    }

    // ===== 새 카테고리 사용 =====

    @Test
    @DisplayName("새 카테고리로 FAQ를 등록하면 이름이 붙고, 이름 전체·일부로 필터할 수 있고, 코드를 바꾸면 이름도 바뀐다")
    void faqUsesNewCategory() throws Exception {
        String tail = tail();
        String firstName = "엘리베이터 고장 " + tail;
        String secondName = "에스컬레이터 고장 " + tail;
        int first = read(body(create(admin, firstName).andExpect(status().isOk())), "$.data.qType");
        int second = read(body(create(admin, secondName).andExpect(status().isOk())), "$.data.qType");

        String question = "새 카테고리 FAQ " + tail;
        String created = body(mvc.perform(post("/api/v1/faq").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("qType", first, "standardQuestion", question, "answer", "답변",
                                "keywords", List.of("승강기"))))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        long faqId = ((Number) read(created, "$.data.faqId")).longValue();
        assertThat((String) read(created, "$.data.category")).isEqualTo(firstName);

        assertThat(faqIdsByCategory(firstName)).containsExactly(faqId);
        assertThat(faqIdsByCategory("엘리베이터 고장")).contains(faqId);
        assertThat(faqIdsByCategory(tail)).contains(faqId);

        // 다른 새 카테고리로, 다시 기존 카테고리로 바꾼다
        updateFaq(faqId, second, question);
        assertThat(detailCategory(faqId)).isEqualTo(secondName);
        assertThat(faqIdsByCategory(firstName)).isEmpty();
        assertThat(faqIdsByCategory(secondName)).containsExactly(faqId);

        updateFaq(faqId, 176, question);
        assertThat(detailCategory(faqId)).isEqualTo("영업/제도");
    }

    @Test
    @DisplayName("후보를 새 카테고리로 수정하고 반영하면 FAQ에 그 카테고리 이름이 붙는다")
    void candidateUsesNewCategory() throws Exception {
        String name = "분실물 보관 " + tail();
        int qType = read(body(create(admin, name).andExpect(status().isOk())), "$.data.qType");

        Long analysisId = analysisWithCandidate();
        String recommendations = recommendations(analysisId);
        Number candidateId = read(recommendations, "$.data.recommendations[0].candidateId");
        Number clusterId = read(recommendations, "$.data.recommendations[0].clusterId");

        String updated = body(mvc.perform(patch("/api/v1/faq/recommendations/" + candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("qType", qType, "standardQuestion", "후보 질문 " + tail(),
                                "answerDraft", "후보 답변")))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        assertThat((String) read(updated, "$.data.category")).isEqualTo(name);

        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("analysisId", analysisId);
        apply.put("clusterId", clusterId);
        apply.put("candidateId", candidateId);
        apply.put("qType", qType);
        apply.put("finalQuestion", "반영 질문 " + tail());
        apply.put("finalAnswer", "반영 답변");
        apply.put("keywords", List.of("분실물"));
        apply.put("isManualPatchConfirmed", true);
        String applied = body(mvc.perform(post("/api/v1/faq/apply").contentType(MediaType.APPLICATION_JSON)
                        .content(json(apply)).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        long faqId = ((Number) read(applied, "$.data.appliedFaqId")).longValue();
        assertThat(detailCategory(faqId)).isEqualTo(name);
        assertThat(jdbcTemplate.queryForObject("SELECT q_type FROM gold_faq WHERE faq_id = ?", Integer.class, faqId))
                .isEqualTo(qType);
    }

    @Test
    @DisplayName("버전을 만들고 CSV로 내려받으면 새 카테고리 이름이 그대로 나온다")
    void exportUsesNewCategoryName() throws Exception {
        String tail = tail();
        String name = "CSV 확인 " + tail;
        int qType = read(body(create(admin, name).andExpect(status().isOk())), "$.data.qType");
        String question = "CSV FAQ " + tail;
        mvc.perform(post("/api/v1/faq").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("qType", qType, "standardQuestion", question, "answer", "CSV 답변",
                                "keywords", List.of("csv"))))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());

        Number versionId = read(body(mvc.perform(post("/api/v1/faq/versions").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())), "$.data.versionId");
        String csv = mvc.perform(get("/api/v1/faq/download?versionId=" + versionId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String line = csv.lines().filter(l -> l.contains(question)).findFirst().orElseThrow();
        assertThat(line).contains("," + name + "," + question + ",");
        // 기존 카테고리 이름도 그대로 나온다
        assertThat(csv).doesNotContain(",null,");
    }

    // ===== 기록·탈퇴 =====

    @Test
    @DisplayName("감사로그에 카테고리 생성(FAQ 분류)이 성공·중복 모두 남는다")
    void auditLogRecordsCategoryCreation() throws Exception {
        String name = "감사로그 " + tail();
        create(admin, name).andExpect(status().isOk());
        create(admin, name).andExpect(status().isConflict());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, category, status FROM gold_audit_log WHERE email = ? AND method = 'POST' AND path = ? "
                        + "ORDER BY log_id", adminEmail, PATH);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("action")).isEqualTo("카테고리 생성");
            assertThat(r.get("category")).isEqualTo("FAQ");
        });
        assertThat(rows.stream().map(r -> ((Number) r.get("status")).intValue()).toList()).containsExactly(200, 409);
    }

    @Test
    @DisplayName("카테고리를 만든 관리자가 탈퇴해도 카테고리는 남고 만든 사람만 비워진다")
    void categorySurvivesCreatorQuit() throws Exception {
        String leavingEmail = newEmail("category-leaving");
        String leaving = token(leavingEmail, ADMIN_CODE);
        String name = "탈퇴 후에도 남음 " + tail();
        int qType = read(body(create(leaving, name).andExpect(status().isOk())), "$.data.qType");

        mvc.perform(delete("/api/v1/auth/quit").header(HttpHeaders.AUTHORIZATION, bearer(leaving)))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, created_by FROM gold_faq_category WHERE q_type = ?", qType);
        assertThat(row.get("name")).isEqualTo(name);
        assertThat(row.get("created_by")).isNull();
        String list = body(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
        assertThat(categoryName(list, qType)).isEqualTo(name);
    }

    // ===== 도우미 =====

    private ResultActions create(String token, String name) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", name))).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private void updateFaq(long faqId, int qType, String question) throws Exception {
        mvc.perform(patch("/api/v1/faq/" + faqId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("qType", qType, "standardQuestion", question, "answer", "답변",
                                "keywords", List.of("승강기"), "editReason", "카테고리 변경")))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
    }

    private String detailCategory(long faqId) throws Exception {
        return read(body(mvc.perform(get("/api/v1/faq/" + faqId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())), "$.data.category");
    }

    private List<Long> faqIdsByCategory(String category) throws Exception {
        String list = body(mvc.perform(get("/api/v1/faq").param("category", category).param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        List<Number> ids = read(list, "$.data.faqList[*].faqId");
        return ids.stream().map(Number::longValue).toList();
    }

    private Long analysisWithCandidate() throws Exception {
        String content = "번호,질문,날짜\n1,카테고리 문의 " + UUID.randomUUID() + ",2026-08-01\n";
        MockMultipartFile file = new MockMultipartFile("file", "category.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        Long analysisId = ((Number) read(body(mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk())), "$.data.analysisId")).longValue();

        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("clusterLabel", 3);
        candidate.put("candidateType", "NEW");
        candidate.put("standardQuestion", "카테고리 후보 " + UUID.randomUUID());
        candidate.put("answerDraft", "후보 답변 초안");
        candidate.put("qType", 176);
        candidate.put("category", "영업/제도");
        candidate.put("similarQuestions", List.of());
        candidate.put("occurrenceCount", 5);
        candidate.put("representativeKeywords", List.of("분실물"));
        candidate.put("synonyms", List.of());
        candidate.put("matchedFaqs", List.of());
        Map<String, Object> cluster = Map.of("clusterLabel", 3, "name", "분실물", "topKeywords", List.of("분실물"), "size", 5);
        Map<String, Object> callback = Map.of("status", "COMPLETED",
                "clusteringView", Map.of("clusterNames", List.of(cluster), "points", List.of()),
                "faqCandidates", List.of(candidate));
        mvc.perform(post("/api/v1/dashboard/analyze/callback/" + analysisId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(callback)).header(HttpHeaders.AUTHORIZATION, "Bearer " + CALLBACK_TOKEN))
                .andExpect(status().isOk());
        return analysisId;
    }

    private String recommendations(Long analysisId) throws Exception {
        return body(mvc.perform(get("/api/v1/faq/recommendations/" + analysisId)
                .header(HttpHeaders.AUTHORIZATION, bearer(admin))).andExpect(status().isOk()));
    }

    private String categoryName(String list, int qType) {
        List<String> names = JsonPath.read(list, "$.data.categories[?(@.qType == " + qType + ")].name");
        assertThat(names).hasSize(1);
        return names.get(0);
    }

    private int categoryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_faq_category", Integer.class);
    }

    private String collation(String table, String column) {
        return jdbcTemplate.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?", String.class, table, column);
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
