package com.breathAI.ttobagi_server.domain.auth.service;

import com.breathAI.ttobagi_server.domain.auth.dto.*;
import com.breathAI.ttobagi_server.domain.auth.dto.UserInfoResponse;
import com.breathAI.ttobagi_server.domain.auth.dto.UserUpdateRequest;
import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.entity.User.Role;
import com.breathAI.ttobagi_server.domain.auth.entity.PasswordResetToken;
import com.breathAI.ttobagi_server.domain.auth.entity.SignupVerification;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.auth.repository.PasswordResetTokenRepository;
import com.breathAI.ttobagi_server.domain.auth.repository.SignupVerificationRepository;
import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisHistoryRepository;
import com.breathAI.ttobagi_server.domain.dashboard.repository.UploadFileRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqActionLogRepository;    
import com.breathAI.ttobagi_server.domain.faq.repository.FaqCandidateRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqEditHistoryRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.RetrieveLogRepository;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.breathAI.ttobagi_server.global.util.JwtUtil;
import com.breathAI.ttobagi_server.global.service.EmailService;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;

// 회원가입, 로그인, 토큰 재발급, 비밀번호 재설정 등 계정 관련 처리
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {
    
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final SignupVerificationRepository signupVerificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    // 회원 탈퇴 시 연관 데이터 정리를 위한 타 도메인 리포지토리
    private final UploadFileRepository uploadFileRepository;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final FaqActionLogRepository faqActionLogRepository;
    private final FaqCandidateRepository faqCandidateRepository;
    private final FaqEditHistoryRepository faqEditHistoryRepository;
    private final FaqRepository faqRepository;
    private final FaqVersionRepository faqVersionRepository;
    private final RetrieveLogRepository retrieveLogRepository;

    // 비밀번호 재설정 인증 코드 규칙 (영문 대소문자 + 숫자, 서로 헷갈리는 0 O 1 l I 는 뺀다)
    private static final String RESET_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int RESET_CODE_LENGTH = 6;
    private static final int MAX_RESET_ATTEMPTS = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // 회원가입 인증 코드는 재설정 코드와 같은 규칙을 쓰고, 메일을 마구 보내지 않도록 다시 받기 간격을 둔다
    private static final int CODE_EXPIRY_MINUTES = 30;
    private static final int SIGNUP_CODE_RESEND_SECONDS = 60;

    // 관리자 승격용 인증 코드 (환경변수 SYSTEM_ADMIN_CODE)
    @Value("${system.admin-code}")
    private String systemAdminCode;

    // 회원가입 인증 코드 발송, 이미 가입된 이메일은 409, 1분 안에 다시 요청하면 429
    // 다시 받으면 이전 코드는 쓸 수 없다
    @Transactional
    public void sendSignupCode(SignupCodeRequest request) {
        String email = request.getEmail().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new CustomException(ErrorCode.EMAIL_DUPLICATED);
        }
        signupVerificationRepository.findByEmail(email).ifPresent(previous -> {
            if (previous.isResentTooSoon(SIGNUP_CODE_RESEND_SECONDS)) {
                throw new CustomException(ErrorCode.SIGNUP_CODE_REQUEST_TOO_SOON);
            }
        });
        signupVerificationRepository.deleteByEmail(email);

        String code = generateCode();
        signupVerificationRepository.save(SignupVerification.builder()
                .email(email)
                .code(code)
                .expiryMinutes(CODE_EXPIRY_MINUTES)
                .build());
        emailService.sendSignupCodeEmail(email, code);
    }

    // 회원가입, 메일로 받은 인증 코드가 맞아야 계정을 만든다. 관리자 코드 일치 시 ADMIN 권한 부여
    // 실패 횟수 기록과 만료 코드 삭제가 예외와 함께 되돌려지지 않도록 CustomException 에는 롤백하지 않는다
    @Transactional(noRollbackFor = CustomException.class)
    public void signUp(SignupRequest request) {
        // 이메일 중복 체크
        if (userRepository.existsByEmail(request.getEmail().toLowerCase())) {
            throw new CustomException(ErrorCode.EMAIL_DUPLICATED);
        }
        verifySignupCode(request.getEmail().toLowerCase(), request.getCode());

        // 관리자 코드 검증 및 Role 결정
        Role role = (request.getAdminCode() != null && request.getAdminCode().equals(systemAdminCode))
                    ? Role.ADMIN : Role.USER;
        
        // entity 생성 및 저장
        User user = User.builder()
            .email(request.getEmail().toLowerCase())
            .password(passwordEncoder.encode(request.getPassword()))
            .role(role)
            .build();
        
        userRepository.save(user);
    }

    // 로그인, 자격 검증 후 액세스·리프레시 토큰 발급
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        LocalDateTime accessTokenExpiresAt = jwtUtil.extractExpiration(accessToken)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();

        LocalDateTime refreshTokenExpiresAt = jwtUtil.extractExpiration(refreshToken)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();
        
            return LoginResponse.builder()
            .accessToken(accessToken)
            .accessTokenExpiresAt(accessTokenExpiresAt)
            .refreshToken(refreshToken)
            .refreshTokenExpiresAt(refreshTokenExpiresAt)
            .tokenType("Bearer")
            .build();
    }

    // 비밀번호 재설정 요청, 6자리 인증 코드 생성 후 메일 발송
    @Transactional
    public PasswordResetResponse resetPassword(PasswordResetRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 기존 코드 삭제 (다시 요청하면 이전 코드는 쓸 수 없다)
        passwordResetTokenRepository.deleteByUser(user);

        // 새 코드 생성
        String token = generateResetCode();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiryMinutes(30)
                .build();

        passwordResetTokenRepository.save(resetToken);

        // 메일 발송
        emailService.sendPasswordResetEmail(user.getEmail(), token);

        return new PasswordResetResponse(user.getEmail());
    }

    // 비밀번호 재설정 확정, 인증 코드 검증 후 변경
    // 코드가 짧으므로 코드만으로 찾지 않고 이메일의 사용자에게 발급된 코드와 비교한다
    // 실패 횟수 기록과 만료 코드 삭제가 예외와 함께 되돌려지지 않도록 CustomException 에는 롤백하지 않는다
    @Transactional(noRollbackFor = CustomException.class)
    public void confirmResetPassword(PasswordResetConfirmRequest request) {
        // 가입 여부가 드러나지 않도록 없는 이메일도 코드 불일치와 같은 응답을 준다
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_RESET_CODE));
        PasswordResetToken resetToken = passwordResetTokenRepository.findByUser(user)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_RESET_CODE));

        if (resetToken.isExpired()) {
            passwordResetTokenRepository.delete(resetToken);
            throw new CustomException(ErrorCode.EXPIRED_RESET_CODE);
        }

        if (resetToken.isUsed()) {
            throw new CustomException(ErrorCode.ALREADY_USED_TOKEN);
        }

        if (resetToken.isLocked(MAX_RESET_ATTEMPTS)) {
            throw new CustomException(ErrorCode.RESET_CODE_ATTEMPTS_EXCEEDED);
        }

        // DB 는 대소문자를 구분하지 않고 비교하므로 여기서 직접 비교한다
        boolean matches = MessageDigest.isEqual(
                resetToken.getToken().getBytes(StandardCharsets.UTF_8),
                request.getToken().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            resetToken.recordFailedAttempt();
            throw new CustomException(ErrorCode.INVALID_RESET_CODE);
        }

        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));

        resetToken.useToken();
    }

    // 영문 대소문자와 숫자로 된 6자리 인증 코드, 이미 발급된 코드와 겹치면 다시 뽑는다
    private String generateResetCode() {
        String code;
        do {
            code = generateCode();
        } while (passwordResetTokenRepository.existsByToken(code));
        return code;
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(RESET_CODE_LENGTH);
        for (int i = 0; i < RESET_CODE_LENGTH; i++) {
            sb.append(RESET_CODE_CHARS.charAt(SECURE_RANDOM.nextInt(RESET_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    // 회원가입 인증 코드 검사, 오류 응답은 비밀번호 재설정 코드와 같다
    // 맞으면 코드를 지워 다시 쓸 수 없게 한다
    private void verifySignupCode(String email, String code) {
        SignupVerification verification = signupVerificationRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_RESET_CODE));

        if (verification.isExpired()) {
            signupVerificationRepository.delete(verification);
            throw new CustomException(ErrorCode.EXPIRED_RESET_CODE);
        }
        if (verification.isLocked(MAX_RESET_ATTEMPTS)) {
            throw new CustomException(ErrorCode.RESET_CODE_ATTEMPTS_EXCEEDED);
        }
        // DB 는 대소문자를 구분하지 않고 비교하므로 여기서 직접 비교한다
        boolean matches = MessageDigest.isEqual(
                verification.getCode().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            verification.recordFailedAttempt();
            throw new CustomException(ErrorCode.INVALID_RESET_CODE);
        }
        signupVerificationRepository.delete(verification);
    }

    // 관리자 권한 승격, 관리자 코드 검증 필요
    // 기존 토큰에는 USER 권한이 남아 있으므로 ADMIN 권한이 담긴 토큰을 새로 발급해 함께 준다
    @Transactional
    public PromoteResponse promoteUser(String email, PromoteRequest request) {
        // 유저 찾기
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 관리자 코드 검증
        if (request.getAdminCode() == null || !request.getAdminCode().equals(systemAdminCode)) {            throw new CustomException(ErrorCode.INVALID_ADMIN_CODE);
        }

        user.updateRole(Role.ADMIN);

        TokenResponse tokens = issueTokens(user);
        return PromoteResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .isActive(user.isActive())
                .createdAt(user.getCreatedAt())
                .accessToken(tokens.getAccessToken())
                .refreshToken(tokens.getRefreshToken())
                .accessTokenExpiresAt(tokens.getAccessTokenExpiresAt())
                .refreshTokenExpiresAt(tokens.getRefreshTokenExpiresAt())
                .tokenType(tokens.getTokenType())
                .build();
    }
    
    // 내 정보 조회
    public UserInfoResponse getMyInfo(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserInfoResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .isActive(user.isActive())
                .createdAt(user.getCreatedAt())
                .build();
    }

    // 내 정보 수정, 현재 비밀번호 확인 후 새 비밀번호로 변경
    @Transactional
    public UserInfoResponse updateMyInfo(String email, UserUpdateRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        user.updatePassword(passwordEncoder.encode(request.getNewPassword()));

        return getMyInfo(email);
    }

    // 회원 탈퇴, 타 도메인의 사용자 참조를 해제한 뒤 계정 삭제
    @Transactional
    public void deleteMyInfo(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        uploadFileRepository.findByUploadedBy(user)
                .forEach(file -> file.clearUploadedBy());
        // 요청 이력은 그 사람의 목록일 뿐이라 지우고, 분석 데이터는 남긴다
        analysisHistoryRepository.deleteByUser(user);
        faqActionLogRepository.clearActedBy(user);
        faqCandidateRepository.clearReviewedBy(user);
        faqEditHistoryRepository.clearEditedBy(user);
        faqRepository.clearCreatedBy(user);
        faqVersionRepository.clearCreatedBy(user);
        retrieveLogRepository.clearUser(user);
        userRepository.delete(user);
    }

    // 리프레시 토큰 검증 후 액세스·리프레시 토큰 재발급
    @Transactional
    public TokenResponse reissue(TokenRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtUtil.validateToken(refreshToken)) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        String email = jwtUtil.extractEmail(refreshToken);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return issueTokens(user);
    }

    // 사용자의 현재 권한으로 액세스·리프레시 토큰 발급
    private TokenResponse issueTokens(User user) {
        String newAccessToken = jwtUtil.generateAccessToken(user);
        String newRefreshToken = jwtUtil.generateRefreshToken(user);

        LocalDateTime newaccessTokenExpiresAt = jwtUtil.extractExpiration(newAccessToken)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();

        LocalDateTime newrefreshTokenExpiresAt = jwtUtil.extractExpiration(newRefreshToken)
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .accessTokenExpiresAt(newaccessTokenExpiresAt)
                .refreshTokenExpiresAt(newrefreshTokenExpiresAt)
                .tokenType("Bearer")
                .build();
    }
    
}
