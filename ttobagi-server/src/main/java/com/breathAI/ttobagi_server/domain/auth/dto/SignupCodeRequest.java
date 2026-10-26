package com.breathAI.ttobagi_server.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원가입 인증 코드 발송 요청, 이메일 규칙은 회원가입과 같다
@Getter
@NoArgsConstructor
public class SignupCodeRequest {

    @NotBlank(message = "이메일은 필수 입력 값입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Pattern(
        regexp = "^[a-zA-Z0-9._%+-]+@seoulmetro\\.co\\.kr$",
        message = "이메일 도메인이 올바르지 않습니다."
    )
    private String email;
}
