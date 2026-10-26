package com.breathAI.ttobagi_server.domain.auth.repository;

import com.breathAI.ttobagi_server.domain.auth.entity.SignupVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// 회원가입 이메일 인증 코드 리포지토리
public interface SignupVerificationRepository extends JpaRepository<SignupVerification, Long> {

    Optional<SignupVerification> findByEmail(String email);

    // 다시 받거나 가입에 성공하면 이전 코드를 지운다
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM SignupVerification v WHERE v.email = :email")
    void deleteByEmail(@Param("email") String email);
}
