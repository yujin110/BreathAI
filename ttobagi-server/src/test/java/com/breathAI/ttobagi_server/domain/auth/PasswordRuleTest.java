package com.breathAI.ttobagi_server.domain.auth;

import com.breathAI.ttobagi_server.domain.auth.dto.PasswordResetConfirmRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.SignupRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.UserUpdateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 비밀번호 규칙: 영문(대소문자)·숫자·특수문자 각 1개 이상, 공백 없는 키보드 문자(ASCII 0x21~0x7E) 6~20자
// 가입·재설정·비밀번호 변경 세 요청이 같은 규칙을 쓰는지 함께 확인한다
class PasswordRuleTest {

    private record Target(Class<?> type, String property) {
    }

    private static final List<Target> TARGETS = List.of(
            new Target(SignupRequest.class, "password"),
            new Target(PasswordResetConfirmRequest.class, "newPassword"),
            new Target(UserUpdateRequest.class, "newPassword"));

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    static Stream<String> validPasswords() {
        return Stream.of(
                // 예전 규칙으로 만든 비밀번호도 계속 쓸 수 있어야 한다
                "abc123!", "qatest1!", "s3oul@metro", "z9&z9&",
                // 대문자
                "Seoul#Metro2026", "Ttobagi24/7", "P@ssw0rd", "MetroLine9!", "ZZZZ9_", "Q1~Q1~",
                // 새로 허용한 특수문자
                "9-Lines-Of-Metro", "{Json:1}", "<Gate>1", "Line_2.Circle", "Exit#3(B)", "Rate=98.8%",
                "\"Quote\"7", "it's1Mine", "C:\\Users\\1", "back`tick1", "pipe|2|Line", "caret^5Up",
                "tilde~7wave", "semi;colon8", "comma,9list", "plus+1Add", "star*4Sky", "[Bracket]0",
                // 공격 문자열처럼 생긴 값도 형식만 맞으면 통과한다(저장 전 암호화되어 그대로 쓰이지 않음)
                "'OR1=1--", "<script>1", "--DROP1;", "${jndi:1}", "../../etc1",
                // 숫자·특수문자 위주
                "1234567!a", "!!!!!!a1", "~`^|0Zz", "2026-10-30T", "010-1234-a!",
                // 경계값: 6자, 20자
                "aB3$eF", "a1!a1!", "Abcdefgh1234567890!@", "Zz9~Zz9~Zz9~Zz9~Zz9~");
    }

    static Stream<String> invalidPasswords() {
        return Stream.of(
                // 비었거나 공백만 있음
                "", "      ",
                // 종류 부족
                "abcdef", "abc123", "ABCDEF12", "abc!@#", "123!@#", "!@#$%^&*", "12345678", "Password", "P@ssword",
                "P4ssw0rd", "1!1!1!1!",
                // 길이: 5자, 21자
                "aB3$e", "a1!", "Abcdefgh1234567890!@#", "Zz9~Zz9~Zz9~Zz9~Zz9~Z",
                // 공백·제어 문자
                "Pass word1!", " Pass1!x", "Pass1!x ", "Pass\t1!x", "Pass\n1!x", "Pass\r1!x", "Pa\u0000ss1!",
                // 한글과 그 밖의 문자
                "비밀번호1!a", "Seoul지하철1!", "ㅁㄴㅇ12!a", "Passwörd1!", "Café1!x", "Ñandu1!x",
                // 전각 문자(눈으로는 영문·숫자처럼 보임)
                "Pass１!ab", "Pass1！ab", "Ａbc123!",
                // 이모지, 보이지 않는 문자, 유사 기호
                "Pass1!😀", "Pass1!\u00A0", "Pass1!\u200B", "Pass1!·a", "Pass1!€", "Pass1!—a", "Pass1!“a”");
    }

    @ParameterizedTest
    @MethodSource("validPasswords")
    @DisplayName("규칙에 맞는 비밀번호는 세 요청 모두 통과한다")
    void acceptsValidPassword(String password) {
        for (Target target : TARGETS) {
            assertTrue(isValid(target, password), target.type().getSimpleName() + " 거부: [" + password + "]");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    @DisplayName("규칙에 맞지 않는 비밀번호는 세 요청 모두 거부한다")
    void rejectsInvalidPassword(String password) {
        for (Target target : TARGETS) {
            assertFalse(isValid(target, password), target.type().getSimpleName() + " 통과: [" + password + "]");
        }
    }

    @Test
    @DisplayName("특수문자 자리에는 공백을 뺀 키보드 문자(0x21~0x7E)만 쓸 수 있다")
    void acceptsOnlyPrintableAsciiAsSpecialCharacter() {
        // 0x00~0x7F 전체와 그 바로 위 문자들을 하나씩 넣어 본다
        IntStream.rangeClosed(0x00, 0xFF).forEach(code -> {
            char c = (char) code;
            if (Character.isLetterOrDigit(c) && code < 0x80) {
                return;
            }
            String password = "Ab1" + c + "xyz";
            boolean expected = code >= 0x21 && code <= 0x7E;
            for (Target target : TARGETS) {
                assertEquals(expected, isValid(target, password),
                        target.type().getSimpleName() + " 문자 0x" + Integer.toHexString(code));
            }
        });
    }

    @Test
    @DisplayName("영문은 대문자만, 소문자만 써도 된다")
    void acceptsEveryAsciiLetter() {
        for (char c = 'A'; c <= 'Z'; c++) {
            for (Target target : TARGETS) {
                assertTrue(isValid(target, c + "1!" + c + "1!"), "대문자 " + c);
                char lower = Character.toLowerCase(c);
                assertTrue(isValid(target, lower + "1!" + lower + "1!"), "소문자 " + lower);
            }
        }
    }

    @Test
    @DisplayName("null은 필수 입력 규칙으로 거부한다")
    void rejectsNull() {
        for (Target target : TARGETS) {
            assertFalse(isValid(target, null), target.type().getSimpleName());
        }
    }

    private boolean isValid(Target target, String password) {
        return validator.validateValue(target.type(), target.property(), password).isEmpty();
    }
}
