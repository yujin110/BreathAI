package com.breathAI.ttobagi_server.global.service;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 비밀번호 재설정 메일의 형식 검증 (실제로 보내지 않는다)
class EmailServiceTest {

    @Test
    @DisplayName("재설정 메일은 글자 본문과 HTML 본문을 함께 담고, HTML에는 코드가 칸마다 한 글자씩 들어간다")
    void resetMailHasPlainAndHtmlBodies() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        EmailService emailService = new EmailService(sender);
        ReflectionTestUtils.setField(emailService, "fromEmail", "sender@example.com");

        emailService.sendPasswordResetEmail("user@seoulmetro.co.kr", "aB3xY9");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        assertEquals("user@seoulmetro.co.kr", message.getAllRecipients()[0].toString());
        assertEquals("[또타24 현행화 시스템] 비밀번호 재설정 안내입니다.", message.getSubject());

        Map<String, String> bodies = new HashMap<>();
        collectBodies(message, bodies);

        // 글자 본문에는 코드가 한 줄로 그대로 들어간다
        assertTrue(bodies.get("text/plain").contains("인증 코드: aB3xY9"), bodies.get("text/plain"));

        // HTML 본문에는 글자마다 칸이 하나씩 있다
        String html = bodies.get("text/html");
        for (char c : "aB3xY9".toCharArray()) {
            assertTrue(html.contains(">" + c + "</div></td>"), "칸에 없는 글자: " + c);
        }
        assertEquals(6, html.split("</div></td>", -1).length - 1);
    }

    @Test
    @DisplayName("가입 인증 메일은 재설정 메일과 같은 양식에 가입 안내 문구를 담는다")
    void signupMailUsesSameLayout() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        EmailService emailService = new EmailService(sender);
        ReflectionTestUtils.setField(emailService, "fromEmail", "sender@example.com");

        emailService.sendSignupCodeEmail("new@seoulmetro.co.kr", "Qw3rT7");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        assertEquals("[또타24 현행화 시스템] 회원가입 인증 코드 안내입니다.", message.getSubject());

        Map<String, String> bodies = new HashMap<>();
        collectBodies(message, bodies);
        assertTrue(bodies.get("text/plain").contains("인증 코드: Qw3rT7"), bodies.get("text/plain"));
        String html = bodies.get("text/html");
        assertTrue(html.contains("회원가입 인증 코드"));
        assertEquals(6, html.split("</div></td>", -1).length - 1);
    }

    private void collectBodies(Part part, Map<String, String> bodies) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collectBodies(multipart.getBodyPart(i), bodies);
            }
        } else if (part.isMimeType("text/plain")) {
            bodies.put("text/plain", (String) content);
        } else if (part.isMimeType("text/html")) {
            bodies.put("text/html", (String) content);
        }
    }
}
