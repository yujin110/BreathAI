package com.breathAI.ttobagi_server.domain.auth.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 회원 정보 수정 요청, 비밀번호만 변경 가능
@Getter
@NoArgsConstructor
public class UserUpdateRequest {

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Pattern(
        regexp = "^[a-zA-Z0-9._%+-]+@seoulmetro\\.co\\.kr$", 
        message = "서울교통공사 이메일만 사용 가능합니다."
    )
    private String email;
    
    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9])[\\x21-\\x7E]{6,20}$",
        message = "비밀번호 형식이 올바르지 않습니다."
    )
    private String newPassword;

}
