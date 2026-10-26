package com.breathAI.ttobagi_server.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원가입 요청
@Getter
@NoArgsConstructor
public class SignupRequest {
    
    @NotBlank(message = "이메일은 필수 입력 값입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Pattern(
        regexp = "^[a-zA-Z0-9._%+-]+@seoulmetro\\.co\\.kr$", 
        message = "이메일 도메인이 올바르지 않습니다."
    )
    private String email;

    @NotBlank(message = "비밀번호는 필수 입력 값입니다.")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*\\d)(?=.*[@$!%*?&])[a-z\\d@$!%*?&]{6,20}$",
        message = "비밀번호 형식이 올바르지 않습니다."
    )
    private String password;

    // 메일로 받은 6자리 인증 코드 (POST /auth/signup/code)
    @NotBlank(message = "인증 코드는 필수 입력 값입니다.")
    private String code;

    private String adminCode;
    
}
