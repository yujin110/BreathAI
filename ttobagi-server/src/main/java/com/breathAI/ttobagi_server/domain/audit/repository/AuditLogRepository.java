package com.breathAI.ttobagi_server.domain.audit.repository;

import com.breathAI.ttobagi_server.domain.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

// 감사로그 리포지토리
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // 감사로그 검색, 조건이 null이면 적용하지 않는다 (이메일·경로는 일부만 맞아도 찾는다)
    @Query("SELECT l FROM AuditLog l WHERE (:from IS NULL OR l.createdAt >= :from) "
            + "AND (:to IS NULL OR l.createdAt < :to) "
            + "AND (:email IS NULL OR l.email LIKE CONCAT('%', :email, '%')) "
            + "AND (:method IS NULL OR l.method = :method) "
            + "AND (:path IS NULL OR l.path LIKE CONCAT('%', :path, '%')) "
            + "AND (:success IS NULL OR l.isSuccess = :success) "
            + "ORDER BY l.createdAt DESC, l.logId DESC")
    Page<AuditLog> search(@Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          @Param("email") String email,
                          @Param("method") String method,
                          @Param("path") String path,
                          @Param("success") Boolean success,
                          Pageable pageable);
}
