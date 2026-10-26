package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회원가입 이메일 인증, 감사로그, 업로드 오류 행 응답 통합 시험
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAuditIntegrationTest {

    private static final String ADMIN_CODE = "test-admin-code";
    private static final String PASSWORD = "qatest1!";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    // ===== 회원가입 이메일 인증 =====

    @Test
    @DisplayName("메일로 받은 코드가 맞아야 가입되고, 쓴 코드는 다시 쓸 수 없다")
    void signupNeedsMailedCode() throws Exception {
        String email = newEmail("signup");
        String code = requestCode(email);

        mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(email, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").exists());
        mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(email, "ZZZZZ2")))
                .andExpect(status().isBadRequest());
        // 대소문자를 구분한다
        mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(email, swapCase(code))))
                .andExpect(status().isBadRequest());
        mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(email, code)))
                .andExpect(status().isCreated());

        // 가입된 이메일은 코드를 다시 받을 수 없다
        mvc.perform(withJson(post("/api/v1/auth/signup/code"), "{\"email\":\"" + email + "\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gold_signup_verification WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    @DisplayName("코드는 1분 안에 다시 받을 수 없고, 다시 받으면 이전 코드는 쓸 수 없다")
    void resendRules() throws Exception {
        String email = newEmail("resend");
        String first = requestCode(email);
        mvc.perform(withJson(post("/api/v1/auth/signup/code"), "{\"email\":\"" + email + "\"}"))
                .andExpect(status().isTooManyRequests());

        // 1분이 지난 것으로 만든다
        jdbcTemplate.update("UPDATE gold_signup_verification SET sent_at = sent_at - INTERVAL 2 MINUTE WHERE email = ?", email);
        String second = requestCode(email, 2);
        if (!first.equals(second)) {
            mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(email, first)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(email, second)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("다섯 번 틀리면 맞는 코드도 막히고(429), 만료된 코드는 400이다")
    void attemptsAndExpiry() throws Exception {
        String locked = newEmail("locked");
        String code = requestCode(locked);
        for (int i = 0; i < 5; i++) {
            mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(locked, "ZZZZZ2")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(locked, code)))
                .andExpect(status().isTooManyRequests());

        String expired = newEmail("expired");
        String expiredCode = requestCode(expired);
        jdbcTemplate.update("UPDATE gold_signup_verification SET expiry_date = NOW(6) - INTERVAL 1 MINUTE WHERE email = ?", expired);
        mvc.perform(withJson(post("/api/v1/auth/signup"), signupBody(expired, expiredCode)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("만료")));

        // 공사 도메인이 아니면 코드를 보내지 않는다
        mvc.perform(withJson(post("/api/v1/auth/signup/code"), "{\"email\":\"someone@example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    // ===== 감사로그 =====

    @Test
    @DisplayName("로그인 실패, 401, 403, 정상 호출을 사용자·결과·IP와 함께 남기고 요청 본문은 남기지 않는다")
    void recordsApiCalls() throws Exception {
        String adminEmail = newEmail("audit-admin");
        String admin = token(adminEmail, ADMIN_CODE);
        String userEmail = newEmail("audit-user");
        String user = token(userEmail, null);

        mvc.perform(withJson(post("/api/v1/auth/login"), "{\"email\":\"" + userEmail + "\",\"password\":\"wrong9!\"}")
                        .header("X-Real-IP", "203.0.113.7"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/faq/categories?probe=" + userEmail.substring(0, 12)).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/faq/versions").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // 화면이 자동으로 부르는 진행 상태 확인
        mvc.perform(get("/api/v1/dashboard/analyze/999999999").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound());

        // 기본 조회는 주요 작업만: 로그인·가입·권한 없음은 보이고 일반 조회와 자동 요청은 안 보인다
        List<Map<String, Object>> logs = logs(admin, "email=" + userEmail);
        Map<String, Object> failedLogin = find(logs, "POST", "/api/v1/auth/login", 401);
        assertThat(failedLogin.get("success")).isEqualTo(false);
        assertThat(failedLogin.get("ip")).isEqualTo("203.0.113.7");
        assertThat(failedLogin.get("action")).isEqualTo("로그인");
        assertThat(failedLogin.get("category")).isEqualTo("AUTH");
        assertThat(failedLogin.get("resultLabel")).isEqualTo("로그인 필요");
        Map<String, Object> denied = find(logs, "POST", "/api/v1/faq/versions", 403);
        assertThat(denied.get("action")).isEqualTo("FAQ 버전 만들기");
        assertThat(denied.get("resultLabel")).isEqualTo("권한 없음");
        // 가입 인증 코드 요청과 가입도 요청에 적힌 이메일로 남는다
        find(logs, "POST", "/api/v1/auth/signup/code", 200);
        assertThat(find(logs, "POST", "/api/v1/auth/signup", 201).get("resultLabel")).isEqualTo("성공");
        assertThat(logs).noneMatch(l -> "/api/v1/faq/categories".equals(l.get("path")));
        assertThat(logs).noneMatch(l -> "/api/v1/dashboard/analyze/999999999".equals(l.get("path")));

        // 조회 포함이면 일반 조회도 보이지만, 자동 요청은 여전히 안 보인다
        List<Map<String, Object>> withReads = logs(admin, "includeReads=true&email=" + userEmail);
        Map<String, Object> read = find(withReads, "GET", "/api/v1/faq/categories", 200);
        assertThat(read.get("query")).isEqualTo("probe=" + userEmail.substring(0, 12));
        assertThat(read.get("category")).isEqualTo("READ");
        assertThat(withReads).noneMatch(l -> "/api/v1/dashboard/analyze/999999999".equals(l.get("path")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT category FROM gold_audit_log WHERE path = '/api/v1/dashboard/analyze/999999999' AND email = ?",
                String.class, userEmail)).isEqualTo("AUTO");

        // 토큰 없는 호출은 사용자 없이 남고, 서버 상태 확인은 남기지 않는다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gold_audit_log WHERE path = '/api/v1/auth/me' AND status = 401 AND email IS NULL",
                Integer.class)).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gold_audit_log WHERE path LIKE '/actuator%'", Integer.class)).isZero();
        // 비밀번호는 어디에도 남지 않는다
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gold_audit_log WHERE path LIKE '%wrong9%' OR query LIKE '%wrong9%' OR query LIKE '%qatest1%'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("감사로그 조회는 관리자만 할 수 있고 결과·경로·메서드·기간으로 거를 수 있다")
    void searchAuditLogs() throws Exception {
        String adminEmail = newEmail("search-admin");
        String admin = token(adminEmail, ADMIN_CODE);
        mvc.perform(get("/api/v1/audit-logs").header(HttpHeaders.AUTHORIZATION, bearer(token(newEmail("search-user"), null))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/faq/versions/999999999/restore").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());

        List<Map<String, Object>> failed = logs(admin, "email=" + adminEmail + "&result=fail&method=post&path=/restore");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).get("status")).isEqualTo(404);
        assertThat(failed.get(0).get("target")).isEqualTo("버전 #999999999");
        assertThat(failed.get(0).get("resultLabel")).isEqualTo("없음");
        assertThat(logs(admin, "email=" + adminEmail + "&from=2000-01-01&to=2000-01-02")).isEmpty();

        // 작업 종류로 거르기, 감사로그 조회 자체는 데이터 반출로 남는다
        List<Map<String, Object>> faqOnly = logs(admin, "email=" + adminEmail + "&category=faq");
        assertThat(faqOnly).isNotEmpty().allMatch(l -> "FAQ".equals(l.get("category")));
        assertThat(logs(admin, "email=" + adminEmail + "&category=EXPORT"))
                .anyMatch(l -> "감사로그 조회".equals(l.get("action")));

        for (String query : List.of("result=maybe", "category=AUTO", "category=NONE")) {
            mvc.perform(get("/api/v1/audit-logs?" + query).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ===== 업로드 오류 행 =====

    @Test
    @DisplayName("업로드 파일에 오류가 있으면 400과 행별 오류 목록을 주고, 분석 기록과 AI 요청을 만들지 않는다")
    void uploadErrorsAreListed() throws Exception {
        String admin = token(newEmail("upload-admin"), ADMIN_CODE);
        String marker = UUID.randomUUID().toString();
        String content = "번호,질문,날짜\n1," + marker + ",2026-08-27\n1,중복,2026-08-27\n2,날짜,8/27/26\n";
        MockMultipartFile file = new MockMultipartFile("file", "bad.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.totalErrors").value(2))
                .andExpect(jsonPath("$.data.errors[0].row").value(3))
                .andExpect(jsonPath("$.data.errors[0].column").value("번호"))
                .andExpect(jsonPath("$.data.errors[1].row").value(4))
                .andExpect(jsonPath("$.data.errors[1].column").value("날짜"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gold_upload_file WHERE original_file_name = 'bad.csv'", Integer.class)).isZero();
        verifyNoInteractions(webClientUtil);
    }

    private String requestCode(String email) throws Exception {
        return requestCode(email, 1);
    }

    // 같은 이메일로 여러 번 받으면 times번째 코드를 돌려준다
    private String requestCode(String email, int times) throws Exception {
        mvc.perform(withJson(post("/api/v1/auth/signup/code"), "{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.times(times)).sendSignupCodeEmail(eq(email), code.capture());
        return code.getValue();
    }

    private List<Map<String, Object>> logs(String token, String query) throws Exception {
        String response = body(mvc.perform(get("/api/v1/audit-logs?size=200&" + query).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andReturn());
        return JsonPath.read(response, "$.data.logs");
    }

    private Map<String, Object> find(List<Map<String, Object>> logs, String method, String path, int status) {
        return logs.stream()
                .filter(l -> method.equals(l.get("method")) && path.equals(l.get("path")) && Integer.valueOf(status).equals(l.get("status")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("감사로그 없음: " + method + " " + path + " " + status + " / " + logs));
    }

    private String signupBody(String email, String code) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\""
                + (code != null ? ",\"code\":\"" + code + "\"" : "") + "}";
    }

    private static String swapCase(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return sb.toString();
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
