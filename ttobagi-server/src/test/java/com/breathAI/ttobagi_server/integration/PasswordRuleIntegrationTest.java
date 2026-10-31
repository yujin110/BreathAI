package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 비밀번호 규칙을 실제 요청으로 확인: 가입·로그인·비밀번호 변경·재설정
// 비밀번호에 따옴표·역슬래시가 들어가므로 요청 본문은 ObjectMapper로 만든다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordRuleIntegrationTest {

    private static final String FORMAT_ERROR = "비밀번호 형식이 올바르지 않습니다.";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @ParameterizedTest
    @ValueSource(strings = {
            "Seoul#Metro2026", "\"Quote\"7", "C:\\Users\\1", "${jndi:1}", "<script>1",
            "it's1Mine", "Abcdefgh1234567890!@", "aB3$eF", "abc123!"})
    @DisplayName("새 규칙의 비밀번호로 가입하고 그대로 로그인할 수 있다")
    void signupAndLoginWithNewRule(String password) throws Exception {
        String email = newEmail("pw");
        signup(email, password).andExpect(status().isCreated());
        login(email, password).andExpect(status().isOk());
    }

    @Test
    @DisplayName("대소문자를 구분해 저장하므로 대소문자가 다르면 로그인할 수 없다")
    void loginIsCaseSensitive() throws Exception {
        String email = newEmail("case");
        signup(email, "Seoul#Metro2026").andExpect(status().isCreated());

        login(email, "seoul#metro2026").andExpect(status().isUnauthorized());
        login(email, "SEOUL#METRO2026").andExpect(status().isUnauthorized());
        login(email, "Seoul#Metro2026").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pass word1!", "비밀번호1!a", "Pass1!😀", "Ａbc123!", "abc123", "aB3$e", "Abcdefgh1234567890!@#"})
    @DisplayName("규칙에 맞지 않는 비밀번호로는 가입할 수 없고 password 칸 오류를 준다")
    void signupRejectsInvalidPassword(String password) throws Exception {
        String email = newEmail("bad");
        signup(email, password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.password").value(FORMAT_ERROR));
    }

    @Test
    @DisplayName("비밀번호 변경: 새 특수문자로 바꿀 수 있고, 규칙에 맞지 않으면 newPassword 칸 오류를 준다")
    void changePasswordWithNewRule() throws Exception {
        String email = newEmail("change");
        String oldPassword = "qatest1!";
        String newPassword = "Line_2.Circle";
        signup(email, oldPassword).andExpect(status().isCreated());
        String token = JsonPath.read(body(login(email, oldPassword)), "$.data.accessToken");

        mvc.perform(withJson(patch("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token),
                        Map.of("email", email, "currentPassword", oldPassword, "newPassword", "Pass1!\u200B")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.newPassword").value(FORMAT_ERROR));

        mvc.perform(withJson(patch("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token),
                        Map.of("email", email, "currentPassword", oldPassword, "newPassword", newPassword)))
                .andExpect(status().isOk());

        login(email, newPassword).andExpect(status().isOk());
        login(email, oldPassword).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비밀번호 재설정: 새 특수문자로 바꿀 수 있고, 규칙에 맞지 않으면 newPassword 칸 오류를 준다")
    void resetPasswordWithNewRule() throws Exception {
        String email = newEmail("reset");
        String newPassword = "[Bracket]0";
        signup(email, "qatest1!").andExpect(status().isCreated());

        mvc.perform(withJson(post("/api/v1/auth/password/reset"), Map.of("email", email)))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(eq(email), code.capture());

        mvc.perform(withJson(post("/api/v1/auth/password/reset/confirm"),
                        Map.of("email", email, "token", code.getValue(), "newPassword", "Passwörd1!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.newPassword").value(FORMAT_ERROR));

        mvc.perform(withJson(post("/api/v1/auth/password/reset/confirm"),
                        Map.of("email", email, "token", code.getValue(), "newPassword", newPassword)))
                .andExpect(status().isOk());

        login(email, newPassword).andExpect(status().isOk());
    }

    private ResultActions signup(String email, String password) throws Exception {
        mvc.perform(withJson(post("/api/v1/auth/signup/code"), Map.of("email", email)))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendSignupCodeEmail(eq(email), code.capture());

        return mvc.perform(withJson(post("/api/v1/auth/signup"),
                Map.of("email", email, "password", password, "code", code.getValue())));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(withJson(post("/api/v1/auth/login"), Map.of("email", email, "password", password)));
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, Map<String, String> body)
            throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    private String body(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String newEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@seoulmetro.co.kr";
    }
}
