package com.breathAI.ttobagi_server.global.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

// 메일 발송 서비스 (비밀번호 재설정, 회원가입 이메일 인증)
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender emailSender;

    // 발신자 주소 (Gmail 사용 시 앱 비밀번호 필요)
    @Value("${spring.mail.username:noreply@ttobagi.com}")
    private String fromEmail;

    // 재설정 인증 코드 안내 메일 발송 (유효기간 30분)
    // 코드를 칸에 한 글자씩 보여주는 HTML 본문과, HTML을 못 여는 메일 프로그램용 글자 본문을 함께 보낸다
    public void sendPasswordResetEmail(String to, String token) {
        String plain = String.format(
            "안녕하세요, 또타24 현행화 시스템입니다.\n\n" +
            "비밀번호 재설정을 위해 아래의 인증 코드를 입력해 주세요.\n" +
            "인증 코드: %s\n\n" +
            "이 코드는 30분 동안만 유효하며, 대소문자를 구분합니다.\n" +
            "본인이 요청하지 않았다면 이 메일을 무시해 주세요.",
            token
        );

        send(to, "[또타24 현행화 시스템] 비밀번호 재설정 안내입니다.", plain,
                buildCodeHtml(token, "비밀번호 재설정 인증 코드", "아래 6자리 코드를 비밀번호 재설정 화면에 입력해 주세요.",
                        "본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 바뀌지 않습니다."));
    }

    // 회원가입 이메일 인증 코드 메일 발송 (유효기간 30분), 재설정 메일과 같은 양식이다
    public void sendSignupCodeEmail(String to, String code) {
        String plain = String.format(
            "안녕하세요, 또타24 현행화 시스템입니다.\n\n" +
            "회원가입을 위해 아래의 인증 코드를 입력해 주세요.\n" +
            "인증 코드: %s\n\n" +
            "이 코드는 30분 동안만 유효하며, 대소문자를 구분합니다.\n" +
            "본인이 요청하지 않았다면 이 메일을 무시해 주세요.",
            code
        );
        send(to, "[또타24 현행화 시스템] 회원가입 인증 코드 안내입니다.", plain,
                buildCodeHtml(code, "회원가입 인증 코드", "아래 6자리 코드를 회원가입 화면에 입력해 주세요.",
                        "본인이 요청하지 않았다면 이 메일을 무시해 주세요. 계정은 만들어지지 않습니다."));
    }

    private void send(String to, String subject, String plain, String html) {
        MimeMessage message = emailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plain, html);
        } catch (MessagingException e) {
            throw new MailPreparationException("인증 코드 메일을 만들지 못했습니다.", e);
        }

        emailSender.send(message);
    }

    // 메일 프로그램은 CSS 지원이 제각각이라 table 과 inline style 만 쓴다
    private String buildCodeHtml(String token, String title, String guide, String footer) {
        StringBuilder cells = new StringBuilder();
        for (char c : token.toCharArray()) {
            cells.append("<td style=\"padding:0 4px;\">")
                 .append("<div style=\"width:52px;height:64px;line-height:64px;border:2px solid #1565C0;")
                 .append("border-radius:10px;background:#F0F6FF;text-align:center;")
                 .append("font-family:Consolas,'Courier New',monospace;font-size:32px;font-weight:700;color:#0D3B7A;\">")
                 .append(c)
                 .append("</div></td>");
        }

        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#F3F4F6;\">"
             + "<tr><td align=\"center\" style=\"padding:24px 0;\">"
             + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#FFFFFF;"
             + "border-radius:12px;font-family:'Malgun Gothic','Apple SD Gothic Neo',Arial,sans-serif;\">"
             + "<tr><td style=\"padding:28px 32px 8px 32px;font-size:20px;font-weight:700;color:#0D3B7A;\">또타24 현행화 시스템</td></tr>"
             + "<tr><td style=\"padding:8px 32px 0 32px;font-size:16px;font-weight:700;color:#111827;\">" + title + "</td></tr>"
             + "<tr><td style=\"padding:8px 32px 20px 32px;font-size:14px;line-height:1.7;color:#4B5563;\">"
             + guide + "</td></tr>"
             + "<tr><td align=\"center\" style=\"padding:0 32px 8px 32px;\">"
             + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>" + cells + "</tr></table>"
             + "</td></tr>"
             + "<tr><td align=\"center\" style=\"padding:12px 32px 4px 32px;font-size:13px;color:#6B7280;\">"
             + "대소문자를 구분합니다. 30분 동안만 유효합니다.</td></tr>"
             + "<tr><td style=\"padding:20px 32px 28px 32px;font-size:12px;line-height:1.7;color:#9CA3AF;border-top:1px solid #E5E7EB;\">"
             + footer + "</td></tr>"
             + "</table></td></tr></table>";
    }
}
