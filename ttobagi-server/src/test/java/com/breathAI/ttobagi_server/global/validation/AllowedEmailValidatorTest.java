package com.breathAI.ttobagi_server.global.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// 공사 메일과 설정으로 허용한 주소만 통과하는지 확인
class AllowedEmailValidatorTest {

    @Test
    @DisplayName("설정이 비어 있으면 공사 메일만 통과한다")
    void companyEmailOnlyWhenNotConfigured() {
        AllowedEmailValidator validator = new AllowedEmailValidator("");

        assertTrue(validator.isValid("user@seoulmetro.co.kr", null));
        assertTrue(validator.isValid("User.Name@seoulmetro.co.kr", null));
        assertFalse(validator.isValid("user@example.com", null));
        assertFalse(validator.isValid("user@seoulmetro.co.kr.example.com", null));
        assertFalse(validator.isValid("user@SEOULMETRO.CO.KR", null));
        assertFalse(validator.isValid("", null));
    }

    @Test
    @DisplayName("설정한 주소는 앞뒤 공백과 대소문자에 상관없이 통과하고, 같은 도메인의 다른 주소는 막는다")
    void configuredEmailsAreAllowed() {
        AllowedEmailValidator validator = new AllowedEmailValidator(" guest@example.com ,Prof@Example.org,, ");

        assertTrue(validator.isValid("guest@example.com", null));
        assertTrue(validator.isValid("GUEST@EXAMPLE.COM", null));
        assertTrue(validator.isValid("prof@example.org", null));
        assertTrue(validator.isValid("user@seoulmetro.co.kr", null));
        assertFalse(validator.isValid("other@example.com", null));
        assertFalse(validator.isValid(" guest@example.com", null));
    }

    @Test
    @DisplayName("값이 없으면 통과시키고 필수 여부는 @NotBlank에 맡긴다")
    void nullIsLeftToNotBlank() {
        assertTrue(new AllowedEmailValidator("").isValid(null, null));
    }
}
