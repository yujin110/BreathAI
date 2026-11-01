package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공사 메일 외 허용 주소를 실제 요청으로 확인
// 허용 주소는 application-test.properties의 TTOBAGI_EXTRA_ALLOWED_EMAILS(guest@example.com, Prof@Example.org)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AllowedEmailIntegrationTest {

    private static final String PASSWORD = "qatest1!";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private WebClientUtil webClientUtil;
    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("허용한 외부 주소는 가입·로그인·비밀번호 찾기를 공사 메일과 똑같이 쓸 수 있다")
    void configuredEmailCanSignupLoginAndReset() throws Exception {
        String email = "guest@example.com";
        signup(email).andExpect(status().isCreated());
        login(email).andExpect(status().isOk());

        perform("/api/v1/auth/password/reset", Map.of("email", email)).andExpect(status().isOk());
        verify(emailService).sendPasswordResetEmail(eq(email), anyString());
    }

    @Test
    @DisplayName("설정에 대문자로 적었거나 대문자로 입력해도 허용하고, 인증 메일은 소문자 주소로 보낸다")
    void configuredEmailIgnoresCase() throws Exception {
        perform("/api/v1/auth/signup/code", Map.of("email", "PROF@EXAMPLE.ORG")).andExpect(status().isOk());
        verify(emailService).sendSignupCodeEmail(eq("prof@example.org"), anyString());
    }

    @Test
    @DisplayName("허용하지 않은 외부 주소는 요청마다 원래 문구로 400을 준다")
    void otherExternalEmailsAreRejected() throws Exception {
        String email = "other@example.com";
        perform("/api/v1/auth/signup/code", Map.of("email", email))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.email").value("이메일 도메인이 올바르지 않습니다."));
        perform("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.email").value("서울교통공사 이메일만 로그인 가능합니다."));
        perform("/api/v1/auth/password/reset", Map.of("email", email))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.email").value("서울교통공사 이메일만 가능합니다."));
    }

    private ResultActions signup(String email) throws Exception {
        perform("/api/v1/auth/signup/code", Map.of("email", email)).andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendSignupCodeEmail(eq(email), code.capture());
        return perform("/api/v1/auth/signup", Map.of("email", email, "password", PASSWORD, "code", code.getValue()));
    }

    private ResultActions login(String email) throws Exception {
        return perform("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
    }

    private ResultActions perform(String path, Map<String, String> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));
    }
}
