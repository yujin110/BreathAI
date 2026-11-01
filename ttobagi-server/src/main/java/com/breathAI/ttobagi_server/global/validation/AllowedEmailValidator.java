package com.breathAI.ttobagi_server.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// 공사 외 주소는 저장소에 남기지 않고 서버 환경변수(TTOBAGI_EXTRA_ALLOWED_EMAILS, 쉼표 구분)로 받는다
// 설정이 비어 있으면 공사 메일만 허용한다
public class AllowedEmailValidator implements ConstraintValidator<AllowedEmail, String> {

    private static final Pattern COMPANY_EMAIL = Pattern.compile("^[a-zA-Z0-9._%+-]+@seoulmetro\\.co\\.kr$");

    private final Set<String> extraEmails;

    public AllowedEmailValidator(@Value("${auth.extra-allowed-emails:}") String extraEmails) {
        this.extraEmails = Arrays.stream(extraEmails.split(","))
                .map(String::strip)
                .filter(email -> !email.isEmpty())
                .map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    // 값이 없으면 @NotBlank가 알린다
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) return true;
        return COMPANY_EMAIL.matcher(value).matches() || extraEmails.contains(value.toLowerCase(Locale.ROOT));
    }
}
