package com.breathAI.ttobagi_server.domain.auth.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 회원가입 이메일 인증 코드, 가입 전에 발급하므로 이메일 기준으로 둔다
@Entity
@Getter
@Table(name = "gold_signup_verification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignupVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id")
    private Long verificationId;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Builder
    public SignupVerification(String email, String code, int expiryMinutes) {
        this.email = email;
        this.code = code;
        this.sentAt = LocalDateTime.now();
        this.expiryDate = this.sentAt.plusMinutes(expiryMinutes);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiryDate);
    }

    public void recordFailedAttempt() {
        this.attemptCount++;
    }

    public boolean isLocked(int maxAttempts) {
        return this.attemptCount >= maxAttempts;
    }

    // 다시 받기 간격이 지나지 않았는지 확인
    public boolean isResentTooSoon(int intervalSeconds) {
        return LocalDateTime.now().isBefore(this.sentAt.plusSeconds(intervalSeconds));
    }
}
