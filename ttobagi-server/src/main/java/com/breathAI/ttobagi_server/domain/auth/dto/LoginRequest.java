package com.breathAI.ttobagi_server.domain.auth.dto;

import com.breathAI.ttobagi_server.global.validation.AllowedEmail;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 로그인 요청
@Getter
@NoArgsConstructor
public class LoginRequest {
    
    @NotBlank(message = "이메일은 필수 입력 값입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @AllowedEmail(message = "서울교통공사 이메일만 로그인 가능합니다.")
    private String email;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    private String password;
    
}
