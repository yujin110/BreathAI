package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.global.service.EmailService;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 시험용 회원가입, 인증 코드는 가짜 메일 서비스(EmailService 모의 객체)에 넘어간 값을 꺼내 쓴다
final class TestSignup {

    private TestSignup() {
    }

    static void signup(MockMvc mvc, EmailService emailService, String email, String password, String adminCode)
            throws Exception {
        mvc.perform(post("/api/v1/auth/signup/code").contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendSignupCodeEmail(eq(email.toLowerCase()), code.capture());

        String admin = adminCode == null ? "" : ",\"adminCode\":\"" + adminCode + "\"";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"code\":\""
                                + code.getValue() + "\"" + admin + "}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201));
    }
}
