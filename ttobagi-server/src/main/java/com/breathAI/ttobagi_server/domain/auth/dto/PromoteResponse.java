package com.breathAI.ttobagi_server.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

// 관리자 권한 승격 응답, 바뀐 권한이 담긴 새 토큰을 함께 준다
// 서버는 토큰의 권한으로 판단하므로 화면은 이 토큰으로 바꿔 써야 바로 관리자 기능을 쓸 수 있다
@Getter
@Builder
public class PromoteResponse {

    private Long userId;

    private String email;

    private String role;

    // UserInfoResponse와 같은 이유로 래퍼 타입을 쓴다
    @JsonProperty("isActive")
    private Boolean isActive;

    @JsonFormat(
        shape = JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
        timezone = "UTC"
    )
    private LocalDateTime createdAt;

    private String accessToken;

    private String refreshToken;

    @JsonFormat(
        shape = JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
        timezone = "UTC"
    )
    private LocalDateTime accessTokenExpiresAt;

    @JsonFormat(
        shape = JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
        timezone = "UTC"
    )
    private LocalDateTime refreshTokenExpiresAt;

    private String tokenType;
}
