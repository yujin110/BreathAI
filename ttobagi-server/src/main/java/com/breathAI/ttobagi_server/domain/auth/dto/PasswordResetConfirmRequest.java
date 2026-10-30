package com.breathAI.ttobagi_server.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 비밀번호 재설정 확인 요청, 메일로 받은 인증 코드와 새 비밀번호 전달
@Getter
@NoArgsConstructor
public class PasswordResetConfirmRequest {

    // 인증 코드가 짧아 코드만으로 사용자를 찾지 않고, 이 이메일의 코드와만 비교한다
    @NotBlank(message = "이메일은 필수 입력 값입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Pattern(
        regexp = "^[a-zA-Z0-9._%+-]+@seoulmetro\\.co\\.kr$",
        message = "서울교통공사 이메일만 가능합니다."
    )
    private String email;

    // 영문 대소문자와 숫자로 된 6자리
    @NotBlank(message = "인증 코드는 필수입니다.")
    @Pattern(regexp = "^[A-Za-z0-9]{6}$", message = "인증 코드 형식이 올바르지 않습니다.")
    private String token;

    @NotBlank(message = "새 비밀번호는 필수 입력 값입니다.")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9])[\\x21-\\x7E]{6,20}$",
        message = "비밀번호 형식이 올바르지 않습니다."
    )
    private String newPassword;
    
}
