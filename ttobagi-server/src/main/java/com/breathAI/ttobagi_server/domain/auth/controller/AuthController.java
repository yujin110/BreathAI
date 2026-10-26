package com.breathAI.ttobagi_server.domain.auth.controller;

import com.breathAI.ttobagi_server.domain.auth.dto.SignupCodeRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.SignupRequest;
import com.breathAI.ttobagi_server.global.dto.ApiResponse;
import com.breathAI.ttobagi_server.domain.auth.dto.LoginRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.LoginResponse;
import com.breathAI.ttobagi_server.domain.auth.dto.PasswordResetResponse;
import com.breathAI.ttobagi_server.domain.auth.dto.PasswordResetRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.PasswordResetConfirmRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.PromoteRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.UserInfoResponse;
import com.breathAI.ttobagi_server.domain.auth.dto.UserUpdateRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.TokenRequest;
import com.breathAI.ttobagi_server.domain.auth.dto.TokenResponse;
import com.breathAI.ttobagi_server.domain.auth.service.AuthService;
import com.breathAI.ttobagi_server.global.security.AuditLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 계정 관련 API
// 가입 인증 코드·가입·로그인·로그아웃·재발급·재설정 요청·재설정 확정만 인증 없이 접근 가능 (SecurityConfig)
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // 회원가입 인증 코드 메일 발송, 가입 전에 먼저 받는다
    @PostMapping("/signup/code")
    public ResponseEntity<ApiResponse<Void>> sendSignupCode(@RequestBody @Valid SignupCodeRequest request,
                                                            HttpServletRequest httpRequest) {
        AuditLogFilter.setRequestEmail(httpRequest, request.getEmail());
        authService.sendSignupCode(request);
        return ResponseEntity.ok(ApiResponse.success(null, "인증 코드를 메일로 보냈습니다."));
    }

    // 회원가입, 메일로 받은 인증 코드 필요, 성공 시 201 반환
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> signup(@RequestBody @Valid SignupRequest request,
                                                    HttpServletRequest httpRequest) {
        AuditLogFilter.setRequestEmail(httpRequest, request.getEmail());
        authService.signUp(request);
        // 본문 status도 HTTP 상태와 같은 201로 맞춘다
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "요청에 성공하였습니다.", null, HttpStatus.CREATED.value()));
    }

    // 로그인, 액세스·리프레시 토큰 발급
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody @Valid LoginRequest request,
                                                            HttpServletRequest httpRequest) {
        // 로그인 실패도 누가 시도했는지 감사로그에 남긴다
        AuditLogFilter.setRequestEmail(httpRequest, request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(authService.login(request), "로그인에 성공하였습니다."));
    }

    // 로그아웃, 토큰 폐기는 클라이언트가 담당하므로 서버는 응답만 반환
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        return ResponseEntity.ok(ApiResponse.success(null, "로그아웃 되었습니다."));
    }

    // 비밀번호 재설정 메일 발송 요청
    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<PasswordResetResponse>> resetPassword(
            @RequestBody @Valid PasswordResetRequest request, HttpServletRequest httpRequest) {
        AuditLogFilter.setRequestEmail(httpRequest, request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(authService.resetPassword(request), "비밀번호 재설정 메일이 발송되었습니다."));
    }

    // 비밀번호 재설정 확정, 메일로 받은 토큰 필요
    @PostMapping("/password/reset/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPassword(
            @RequestBody @Valid PasswordResetConfirmRequest request, HttpServletRequest httpRequest) {
        AuditLogFilter.setRequestEmail(httpRequest, request.getEmail());
        authService.confirmResetPassword(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 내 정보 조회
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserInfoResponse>> getMyInfo(
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(authService.getMyInfo(email), "사용자 정보를 성공적으로 불러왔습니다."));
    }

    // 내 정보 수정, 비밀번호만 변경 가능
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserInfoResponse>> updateMyInfo(
            @AuthenticationPrincipal String email,
            @RequestBody @Valid UserUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.updateMyInfo(email, request), "사용자 정보가 성공적으로 수정되었습니다."));
    }

    // 관리자 권한 승격, 관리자 코드 필요
    @PatchMapping("/promote")
    public ResponseEntity<ApiResponse<UserInfoResponse>> promoteUser(
            @AuthenticationPrincipal String email,
            @RequestBody @Valid PromoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.promoteUser(email, request)));
    }

    // 회원 탈퇴
    @DeleteMapping("/quit")
    public ResponseEntity<ApiResponse<Void>> deleteMyInfo(
            @AuthenticationPrincipal String email) {
        authService.deleteMyInfo(email);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 토큰 재발급, 만료된 액세스 토큰 대신 리프레시 토큰으로 요청
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<TokenResponse>> reissue(@RequestBody TokenRequest request) {
        TokenResponse response = authService.reissue(request);
        return ResponseEntity.ok(ApiResponse.success(response, "토큰이 재발급되었습니다."));
    }
}
