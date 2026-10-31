package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FAQ 일괄 업로드: 추가·수정·변경 없음, 열 없음과 빈칸, 파일 안 중복, DB의 같은 질문 FAQ(고르기·건너뛰기), 오류, 기록
// 테스트 DB를 다른 시험과 함께 쓰므로 질문·카테고리에는 무작위 꼬리를 붙이고, 파일에는 이 시험의 FAQ만 넣는다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqImportIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String PASSWORD = "qatest1!";
    private static final String PATH = "/api/v1/faq/import";
    private static final String HEADER = "ID,카테고리,질문,답변,키워드,유사어\n";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    private String adminEmail;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        adminEmail = newEmail("import-admin");
        admin = token(adminEmail, ADMIN_CODE);
    }

    // ===== 추가 =====

    @Test
    @DisplayName("CSV 추가: 기존 카테고리는 공백이 달라도 같은 코드, 없는 카테고리는 새로 만들고, 키워드·유사어를 정리해 저장한다")
    void addsFaqsFromCsv() throws Exception {
        String t = tail();
        String newCategory = "새 분류 " + t;
        String csv = HEADER
                + ",영업 / 제도,추가 질문1 " + t + ",답변1,\"k1, k2,, k1\",\" s1 ,S1, s2\"\n"
                + "," + newCategory + ",추가 질문2 " + t + ",\"여러 줄\n답변2\",,\n";
        String response = body(upload(admin, csvFile(csv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("FAQ 파일을 반영했습니다.")));

        assertThat((Integer) read(response, "$.data.addedCount")).isEqualTo(2);
        assertThat((Integer) read(response, "$.data.updatedCount")).isZero();
        assertThat((Integer) read(response, "$.data.skippedCount")).isZero();
        assertThat(this.<List<String>>read(response, "$.data.newCategories")).containsExactly(newCategory);

        Map<String, Object> first = faqByQuestion("추가 질문1 " + t);
        assertThat(((Number) first.get("q_type")).intValue()).isEqualTo(176);
        assertThat(first.get("category")).isEqualTo("영업/제도");
        assertThat(json(first.get("keywords"))).containsExactly("k1", "k2");
        assertThat(json(first.get("synonyms"))).containsExactly("s1", "s2");

        Map<String, Object> second = faqByQuestion("추가 질문2 " + t);
        assertThat(second.get("answer")).isEqualTo("여러 줄\n답변2");
        assertThat(second.get("category")).isEqualTo(newCategory);
        assertThat(((Number) second.get("q_type")).intValue()).isGreaterThanOrEqualTo(100000);
        assertThat(json(second.get("keywords"))).isEmpty();
        assertThat(categoryNames()).contains(newCategory);

        // 이력은 등록(사유 포함), 버전(UPLOAD)에 묶인다
        Number versionId = read(response, "$.data.versionId");
        assertThat(jdbcTemplate.queryForObject("SELECT version_type FROM gold_faq_version WHERE version_id = ?",
                String.class, versionId.longValue())).isEqualTo("UPLOAD");
        Map<String, Object> history = jdbcTemplate.queryForMap("SELECT edit_type, edit_reason, version_id FROM gold_faq_edit_history "
                + "WHERE faq_id = ? ORDER BY history_id DESC LIMIT 1", ((Number) first.get("faq_id")).longValue());
        assertThat(history.get("edit_type")).isEqualTo("CREATE");
        assertThat(history.get("edit_reason")).isEqualTo("파일 일괄 업로드");
        assertThat(((Number) history.get("version_id")).longValue()).isEqualTo(versionId.longValue());
    }

    @Test
    @DisplayName("XLSX 추가: 빈 행은 건너뛰고, ID 열이 엉터리여도 무시한다")
    void addsFaqsFromXlsx() throws Exception {
        String t = tail();
        byte[] xlsx = xlsx(List.of(
                List.of("ID", "카테고리", "질문", "답변", "키워드"),
                List.of("abc", "승차권/운임", "엑셀 질문1 " + t, "엑셀 답변1", "엑1, 엑2"),
                List.of("", "", "", "", ""),
                List.of("999999999", "", "엑셀 질문2 " + t, "엑셀 답변2", "")));
        String response = body(upload(admin, new MockMultipartFile("file", "faq.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)).andExpect(status().isOk()));

        assertThat((Integer) read(response, "$.data.addedCount")).isEqualTo(2);
        Map<String, Object> first = faqByQuestion("엑셀 질문1 " + t);
        assertThat(((Number) first.get("q_type")).intValue()).isEqualTo(172);
        assertThat(json(first.get("keywords"))).containsExactly("엑1", "엑2");
        Map<String, Object> second = faqByQuestion("엑셀 질문2 " + t);
        assertThat(second.get("q_type")).isNull();
        assertThat(second.get("category")).isNull();
    }

    @Test
    @DisplayName("같은 질문의 FAQ가 삭제돼 있으면 새로 추가하고, 질문 공백만 다르면 같은 FAQ로 본다(질문은 바꾸지 않음)")
    void matchesActiveFaqByNormalizedQuestion() throws Exception {
        String t = tail();
        long deleted = createFaq("삭제된 질문 " + t, "답", List.of(), List.of(), 176);
        mvc.perform(delete("/api/v1/faq/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        long spaced = createFaq("환승  방법 " + t, "예전 답", List.of(), List.of(), 176);

        String response = body(upload(admin, csvFile("질문,답변\n"
                + "삭제된 질문 " + t + ",답\n"
                + "\"  환승 방법 " + t + " \",새 답\n")).andExpect(status().isOk()));
        assertThat((Integer) read(response, "$.data.addedCount")).isEqualTo(1);
        assertThat((Integer) read(response, "$.data.updatedCount")).isEqualTo(1);
        assertThat(((Number) read(response, "$.data.updated[0].faqId")).longValue()).isEqualTo(spaced);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_faq WHERE question = ? AND is_active = 1",
                Integer.class, "삭제된 질문 " + t)).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT question, answer FROM gold_faq WHERE faq_id = ?", spaced);
        assertThat(row.get("question")).isEqualTo("환승  방법 " + t);
        assertThat(row.get("answer")).isEqualTo("새 답");
    }

    // ===== 수정·변경 없음 =====

    @Test
    @DisplayName("수정: 답변·카테고리·키워드·유사어가 각각 바뀐 FAQ만 그 칸을 바꾸고 이력에 전후 값을 남긴다")
    void updatesOnlyChangedFields() throws Exception {
        String t = tail();
        long answer = createFaq("답변 변경 " + t, "옛 답변", List.of("k"), List.of("s"), 176);
        long category = createFaq("카테고리 변경 " + t, "답", List.of("k"), List.of("s"), 176);
        long keywords = createFaq("키워드 변경 " + t, "답", List.of("k"), List.of("s"), 176);
        long synonyms = createFaq("유사어 변경 " + t, "답", List.of("k"), List.of("s"), 176);

        String csv = HEADER
                + ",영업/제도,답변 변경 " + t + ",새 답변,k,s\n"
                + ",승차권/운임,카테고리 변경 " + t + ",답,k,s\n"
                + ",영업/제도,키워드 변경 " + t + ",답,\"k, k2\",s\n"
                + ",영업/제도,유사어 변경 " + t + ",답,k,\"s, s2\"\n";
        String response = body(upload(admin, csvFile(csv)).andExpect(status().isOk()));
        assertThat((Integer) read(response, "$.data.updatedCount")).isEqualTo(4);
        assertThat(changedFields(response, answer)).containsExactly("answer");
        assertThat(changedFields(response, category)).containsExactly("category");
        assertThat(changedFields(response, keywords)).containsExactly("keywords");
        assertThat(changedFields(response, synonyms)).containsExactly("synonyms");

        assertThat(faqRow(answer).get("answer")).isEqualTo("새 답변");
        assertThat(((Number) faqRow(category).get("q_type")).intValue()).isEqualTo(172);
        assertThat(faqRow(category).get("category")).isEqualTo("승차권/운임");
        assertThat(json(faqRow(keywords).get("keywords"))).containsExactly("k", "k2");
        assertThat(json(faqRow(synonyms).get("synonyms"))).containsExactly("s", "s2");

        Map<String, Object> history = jdbcTemplate.queryForMap("SELECT edit_type, edit_reason, before_answer, after_answer "
                + "FROM gold_faq_edit_history WHERE faq_id = ? ORDER BY history_id DESC LIMIT 1", answer);
        assertThat(history.get("edit_type")).isEqualTo("MANUAL");
        assertThat(history.get("edit_reason")).isEqualTo("파일 일괄 업로드");
        assertThat(history.get("before_answer")).isEqualTo("옛 답변");
        assertThat(history.get("after_answer")).isEqualTo("새 답변");
    }

    @Test
    @DisplayName("FAQ 다운로드 파일을 그대로 다시 올리면 모두 변경 없음이고 버전을 만들지 않는다")
    void reimportingDownloadChangesNothing() throws Exception {
        String t = tail();
        createFaq("왕복 1 " + t, "답변, 쉼표 포함", List.of("k1", "k2"), List.of("s1"), 176);
        createFaq("왕복 2 " + t, "<p>HTML 답변</p>\n둘째 줄", List.of(), List.of(), 188);
        createFaq("왕복 3 " + t, "카테고리 없음", List.of("단독"), List.of(), null);

        String csv = mvc.perform(get("/api/v1/faq/download").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 머리글과 이 시험의 FAQ 행만 남긴다 (답변이 여러 줄이라 CSV로 읽어서 다시 쓴다)
        String mine = rewriteCsvKeeping(csv, t);
        int versions = versionCount();

        String response = body(upload(admin, csvFile(mine)).andExpect(status().isOk()));
        assertThat((Integer) read(response, "$.data.unchangedCount")).isEqualTo(3);
        assertThat((Integer) read(response, "$.data.addedCount")).isZero();
        assertThat((Integer) read(response, "$.data.updatedCount")).isZero();
        assertThat((Object) read(response, "$.data.versionId")).isNull();
        assertThat(versionCount()).isEqualTo(versions);
    }

    @Test
    @DisplayName("열이 없으면 그 항목은 그대로, 열은 있는데 칸이 비면 그 항목을 비운다")
    void missingColumnKeepsAndBlankCellClears() throws Exception {
        String t = tail();
        long faqId = createFaq("빈칸 시험 " + t, "답", List.of("k"), List.of("s"), 176);

        // 키워드·유사어·카테고리 열 없음 → 변경 없음
        String kept = body(upload(admin, csvFile("질문,답변\n빈칸 시험 " + t + ",답\n")).andExpect(status().isOk()));
        assertThat((Integer) read(kept, "$.data.unchangedCount")).isEqualTo(1);

        // 열은 있는데 칸이 비면 비운다
        String cleared = body(upload(admin, csvFile(HEADER + ",,빈칸 시험 " + t + ",답,,\n")).andExpect(status().isOk()));
        assertThat(changedFields(cleared, faqId)).containsExactly("category", "keywords", "synonyms");
        Map<String, Object> row = faqRow(faqId);
        assertThat(row.get("q_type")).isNull();
        assertThat(row.get("category")).isNull();
        assertThat(json(row.get("keywords"))).isEmpty();
        assertThat(json(row.get("synonyms"))).isEmpty();
    }

    // ===== 파일 안 중복 =====

    @Test
    @DisplayName("파일 안에서 질문과 내용이 모두 같으면 한 건으로 반영하고 합친 행을 알려 준다")
    void mergesIdenticalDuplicateRows() throws Exception {
        String t = tail();
        String csv = HEADER
                + ",영업/제도,중복 질문 " + t + ",답,\"k1, k2\",s\n"
                + ",영업 / 제도,\" 중복  질문 " + t + "\",답,\"k1,k2\",s\n";
        String response = body(upload(admin, csvFile(csv)).andExpect(status().isOk()));
        assertThat((Integer) read(response, "$.data.addedCount")).isEqualTo(1);
        assertThat(this.<List<Integer>>read(response, "$.data.mergedDuplicates[0].rows")).containsExactly(2, 3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gold_faq WHERE question = ?", Integer.class,
                "중복 질문 " + t)).isEqualTo(1);
    }

    @Test
    @DisplayName("파일 안에서 질문이 같은데 내용이 다르면 400이고 아무것도 저장하지 않는다")
    void rejectsConflictingDuplicateRows() throws Exception {
        String t = tail();
        Snapshot before = snapshot();
        String csv = HEADER
                + ",새 분류 " + t + ",충돌 질문 " + t + ",답1,,\n"
                + ",,다른 질문 " + t + ",답,,\n"
                + ",,충돌 질문 " + t + ",답2,,\n";
        String response = body(upload(admin, csvFile(csv)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("파일 내용에 오류가 있습니다. 오류 목록을 확인해 주세요.")));
        assertThat((Integer) read(response, "$.data.totalErrors")).isEqualTo(1);
        assertThat((Integer) read(response, "$.data.errors[0].row")).isEqualTo(4);
        assertThat((String) read(response, "$.data.errors[0].reason")).isEqualTo("2행과 질문이 같은데 내용이 다릅니다.");
        assertThat(snapshot()).isEqualTo(before);
    }

    // ===== DB에 같은 질문의 FAQ가 여러 개 =====

    @Test
    @DisplayName("DB에 같은 질문 FAQ가 여러 개면 ID → 같은 내용 → 카테고리 순으로 고르고, 못 고르면 그 행만 건너뛰고 알린다")
    void picksAmongDuplicateQuestionsOrSkips() throws Exception {
        String t = tail();
        String byIdQuestion = "ID로 고름 " + t;
        long idA = insertFaq(byIdQuestion, "답A", 172);
        long idB = insertFaq(byIdQuestion, "답B", 176);
        String sameQuestion = "같은 내용 " + t;
        insertFaq(sameQuestion, "답A", 172);
        insertFaq(sameQuestion, "답B", 176);
        String byCategoryQuestion = "카테고리로 고름 " + t;
        long catA = insertFaq(byCategoryQuestion, "답A", 172);
        long catB = insertFaq(byCategoryQuestion, "답B", 176);
        String skipQuestion = "못 고름 " + t;
        long skipA = insertFaq(skipQuestion, "답A", 172);
        long skipB = insertFaq(skipQuestion, "답B", 176);

        String csv = "ID,카테고리,질문,답변\n"
                + idB + ",영업/제도," + byIdQuestion + ",ID로 고친 답\n"
                + ",영업/제도," + sameQuestion + ",답B\n"
                + ",승차권/운임," + byCategoryQuestion + ",카테고리로 고친 답\n"
                + ",열차운영관련," + skipQuestion + ",건너뛸 답\n"
                + ",,함께 추가 " + t + ",정상 반영\n";
        String response = body(upload(admin, csvFile(csv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "FAQ 파일을 반영했습니다. 1건은 같은 질문의 FAQ가 여러 개라 건너뛰었습니다. 건너뛴 행을 확인해 주세요.")));

        assertThat((Integer) read(response, "$.data.updatedCount")).isEqualTo(2);
        assertThat((Integer) read(response, "$.data.unchangedCount")).isEqualTo(1);
        assertThat((Integer) read(response, "$.data.addedCount")).isEqualTo(1);
        assertThat((Integer) read(response, "$.data.skippedCount")).isEqualTo(1);
        assertThat((Integer) read(response, "$.data.skipped[0].row")).isEqualTo(5);
        assertThat((String) read(response, "$.data.skipped[0].standardQuestion")).isEqualTo(skipQuestion);
        assertThat(this.<List<Number>>read(response, "$.data.skipped[0].candidateFaqIds").stream().map(Number::longValue))
                .containsExactly(skipA, skipB);
        assertThat((String) read(response, "$.data.skipped[0].reason"))
                .isEqualTo("같은 질문의 FAQ가 여러 개라 고칠 FAQ를 정할 수 없음");

        assertThat(faqRow(idB).get("answer")).isEqualTo("ID로 고친 답");
        assertThat(faqRow(idA).get("answer")).isEqualTo("답A");
        assertThat(faqRow(catA).get("answer")).isEqualTo("카테고리로 고친 답");
        assertThat(faqRow(catB).get("answer")).isEqualTo("답B");
        assertThat(faqRow(skipA).get("answer")).isEqualTo("답A");
        assertThat(faqRow(skipB).get("answer")).isEqualTo("답B");
    }

    @Test
    @DisplayName("건너뛴 행만 있고 바뀐 게 없으면 버전을 만들지 않는다")
    void skippedOnlyMakesNoVersion() throws Exception {
        String t = tail();
        String question = "건너뜀만 " + t;
        insertFaq(question, "답A", 172);
        insertFaq(question, "답B", 176);
        int versions = versionCount();

        String response = body(upload(admin, csvFile("질문,답변\n" + question + ",새 답\n")).andExpect(status().isOk()));
        assertThat((Integer) read(response, "$.data.skippedCount")).isEqualTo(1);
        assertThat((Object) read(response, "$.data.versionId")).isNull();
        assertThat(versionCount()).isEqualTo(versions);
    }

    // ===== 오류 =====

    @Test
    @DisplayName("오류: 필수 열 없음, 빈 질문·답변(CSV 줄 번호), 카테고리 100자 초과는 400이고 아무것도 저장하지 않는다")
    void rejectsInvalidCsvRows() throws Exception {
        String t = tail();
        Snapshot before = snapshot();

        String noAnswer = body(upload(admin, csvFile("질문,키워드\n질문 " + t + ",k\n")).andExpect(status().isBadRequest()));
        assertThat((String) read(noAnswer, "$.data.errors[0].column")).isEqualTo("답변");
        assertThat((String) read(noAnswer, "$.data.errors[0].reason")).isEqualTo("필수 열이 없습니다.");
        assertThat((Integer) read(noAnswer, "$.data.errors[0].row")).isEqualTo(1);

        // 2~3줄은 여러 줄 질문 한 행, 4줄 빈 질문, 5줄 빈 답변, 6줄 카테고리 101자
        String csv = "카테고리,질문,답변\n"
                + ",\"여러\n줄 질문 " + t + "\",답\n"
                + ",,답\n"
                + ",질문만 " + t + ",\n"
                + "가".repeat(101) + ",긴 카테고리 " + t + ",답\n";
        String rows = body(upload(admin, csvFile(csv)).andExpect(status().isBadRequest()));
        assertThat((Integer) read(rows, "$.data.totalErrors")).isEqualTo(3);
        assertThat(this.<List<Integer>>read(rows, "$.data.errors[*].row")).containsExactly(4, 5, 6);
        assertThat(this.<List<String>>read(rows, "$.data.errors[*].column")).containsExactly("질문", "답변", "카테고리");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("오류: XLSX 빈 질문은 엑셀 행 번호로 알려 준다")
    void reportsExcelRowNumbers() throws Exception {
        byte[] xlsx = xlsx(List.of(
                List.of("질문", "답변"),
                List.of("엑셀 정상 " + tail(), "답"),
                List.of("", ""),
                List.of("", "질문 없음")));
        String response = body(upload(admin, new MockMultipartFile("file", "bad.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)).andExpect(status().isBadRequest()));
        assertThat((Integer) read(response, "$.data.errors[0].row")).isEqualTo(4);
        assertThat((String) read(response, "$.data.errors[0].column")).isEqualTo("질문");
    }

    @Test
    @DisplayName("오류: UTF-8이 아닌 CSV, 다른 확장자, 빈 파일, 머리글만 있는 파일은 400")
    void rejectsUnreadableFiles() throws Exception {
        Snapshot before = snapshot();
        String eucKr = body(upload(admin, new MockMultipartFile("file", "euc.csv", "text/csv",
                ("질문,답변\n가,나\n").getBytes(Charset.forName("EUC-KR")))).andExpect(status().isBadRequest()));
        assertThat((String) read(eucKr, "$.data.errors[0].reason")).isEqualTo("CSV 파일을 UTF-8로 읽을 수 없습니다. UTF-8로 저장해 주세요.");

        upload(admin, new MockMultipartFile("file", "faq.txt", "text/plain", "질문,답변\n가,나\n".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("CSV 또는 XLSX 파일만 업로드할 수 있습니다."));
        upload(admin, new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0])).andExpect(status().isBadRequest());
        String headerOnly = body(upload(admin, csvFile("질문,답변\n")).andExpect(status().isBadRequest()));
        assertThat((String) read(headerOnly, "$.data.errors[0].reason")).isEqualTo("데이터 행이 없습니다.");
        assertThat(snapshot()).isEqualTo(before);
    }

    // ===== 권한·기록 =====

    @Test
    @DisplayName("일반 계정은 403, 토큰 없이는 401이고 아무것도 저장하지 않는다")
    void requiresAdmin() throws Exception {
        Snapshot before = snapshot();
        String csv = "질문,답변\n권한 시험 " + tail() + ",답\n";
        upload(token(newEmail("import-user"), null), csvFile(csv)).andExpect(status().isForbidden());
        mvc.perform(multipart(PATH).file(csvFile(csv))).andExpect(status().isUnauthorized());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("감사로그에 FAQ 일괄 업로드(FAQ 분류)가 성공·실패 모두 남는다")
    void auditLogRecordsImport() throws Exception {
        upload(admin, csvFile("질문,답변\n감사로그 " + tail() + ",답\n")).andExpect(status().isOk());
        upload(admin, csvFile("질문\n답변 열 없음\n")).andExpect(status().isBadRequest());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, category, status FROM gold_audit_log WHERE email = ? AND method = 'POST' AND path = ? ORDER BY log_id",
                adminEmail, PATH);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("action")).isEqualTo("FAQ 일괄 업로드");
            assertThat(r.get("category")).isEqualTo("FAQ");
        });
        assertThat(rows.stream().map(r -> ((Number) r.get("status")).intValue()).toList()).containsExactly(200, 400);
    }

    // ===== 도우미 =====

    private record Snapshot(int faqs, int categories, int versions, int histories) {}

    private Snapshot snapshot() {
        return new Snapshot(count("gold_faq"), count("gold_faq_category"), count("gold_faq_version"), count("gold_faq_edit_history"));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int versionCount() {
        return count("gold_faq_version");
    }

    private ResultActions upload(String token, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart(PATH).file(file).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "faq.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] xlsx(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("FAQ");
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    if (!rows.get(r).get(c).isEmpty()) row.createCell(c).setCellValue(rows.get(r).get(c));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    // 다운로드한 CSV에서 머리글과 표시가 들어간 행만 다시 CSV로 쓴다
    private String rewriteCsvKeeping(String csv, String mark) throws Exception {
        var format = org.apache.commons.csv.CSVFormat.DEFAULT;
        var out = new java.io.StringWriter();
        try (var parser = org.apache.commons.csv.CSVParser.parse(csv.substring(1), format);
             var printer = new org.apache.commons.csv.CSVPrinter(out, format)) {
            boolean header = true;
            for (var record : parser) {
                if (header || record.get(2).contains(mark)) printer.printRecord(record);
                header = false;
            }
        }
        return out.toString();
    }

    private long createFaq(String question, String answer, List<String> keywords, List<String> synonyms, Integer qType)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("qType", qType);
        request.put("standardQuestion", question);
        request.put("answer", answer);
        request.put("keywords", keywords);
        request.put("synonyms", synonyms);
        String response = body(mvc.perform(post("/api/v1/faq").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk()));
        return ((Number) read(response, "$.data.faqId")).longValue();
    }

    // 같은 질문의 FAQ를 여럿 만들려고 DB에 바로 넣는다 (카테고리 이름은 마스터에서 찾음)
    private long insertFaq(String question, String answer, int qType) {
        String category = jdbcTemplate.queryForObject(
                "SELECT q_disp_name FROM bronze_cate_info WHERE q_type = ? LIMIT 1", String.class, qType);
        jdbcTemplate.update("INSERT INTO gold_faq (question, answer, keywords, synonyms, q_type, category) VALUES (?, ?, '[]', '[]', ?, ?)",
                question, answer, qType, category);
        return jdbcTemplate.queryForObject("SELECT MAX(faq_id) FROM gold_faq WHERE question = ?", Long.class, question);
    }

    private Map<String, Object> faqByQuestion(String question) {
        return jdbcTemplate.queryForMap("SELECT faq_id, question, answer, keywords, synonyms, q_type, category "
                + "FROM gold_faq WHERE question = ? AND is_active = 1", question);
    }

    private Map<String, Object> faqRow(long faqId) {
        return jdbcTemplate.queryForMap("SELECT answer, keywords, synonyms, q_type, category FROM gold_faq WHERE faq_id = ?", faqId);
    }

    private List<String> changedFields(String response, long faqId) {
        List<List<String>> fields = JsonPath.read(response, "$.data.updated[?(@.faqId == " + faqId + ")].changedFields");
        assertThat(fields).hasSize(1);
        return fields.get(0);
    }

    private List<String> categoryNames() throws Exception {
        return read(body(mvc.perform(get("/api/v1/faq/categories").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())), "$.data.categories[*].name");
    }

    private List<String> json(Object value) throws Exception {
        return value == null ? List.of() : List.of(objectMapper.readValue(value.toString(), String[].class));
    }

    private String token(String email, String adminCode) throws Exception {
        TestSignup.signup(mvc, emailService, email, PASSWORD, adminCode);
        return read(body(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())), "$.data.accessToken");
    }

    private String tail() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String newEmail(String prefix) {
        return prefix + "-" + tail() + "@seoulmetro.co.kr";
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
