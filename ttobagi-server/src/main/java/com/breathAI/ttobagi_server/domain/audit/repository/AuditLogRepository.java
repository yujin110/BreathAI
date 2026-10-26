package com.breathAI.ttobagi_server.domain.audit.repository;

import com.breathAI.ttobagi_server.domain.audit.entity.AuditCategory;
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
    // 자동 요청(hidden)은 항상 빼고, includeReads가 false면 조회(read)는 401·403으로 막힌 것만 보인다
    // 분류가 없는 기록(표에 없는 GET 외 요청)은 주요 작업으로 본다
    @Query("SELECT l FROM AuditLog l WHERE (l.category IS NULL OR l.category <> :hidden) "
            + "AND (:includeReads = true OR l.category IS NULL OR l.category <> :read OR l.status IN (401, 403)) "
            + "AND (:category IS NULL OR l.category = :category) "
            + "AND (:from IS NULL OR l.createdAt >= :from) "
            + "AND (:to IS NULL OR l.createdAt < :to) "
            + "AND (:email IS NULL OR l.email LIKE CONCAT('%', :email, '%')) "
            + "AND (:method IS NULL OR l.method = :method) "
            + "AND (:path IS NULL OR l.path LIKE CONCAT('%', :path, '%')) "
            + "AND (:success IS NULL OR l.isSuccess = :success) "
            + "ORDER BY l.createdAt DESC, l.logId DESC")
    Page<AuditLog> search(@Param("hidden") AuditCategory hidden,
                          @Param("read") AuditCategory read,
                          @Param("includeReads") boolean includeReads,
                          @Param("category") AuditCategory category,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          @Param("email") String email,
                          @Param("method") String method,
                          @Param("path") String path,
                          @Param("success") Boolean success,
                          Pageable pageable);
}
