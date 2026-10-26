package com.breathAI.ttobagi_server.domain.audit.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

// API 호출 감사로그, 탈퇴해도 남도록 계정과 연결하지 않고 이메일을 글자로 저장한다
@Entity
@Getter
@Table(name = "gold_audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "email")
    private String email;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 500)
    private String path;

    @Column(name = "query", length = 1000)
    private String query;

    @Column(name = "status", nullable = false)
    private Integer status;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "is_success", nullable = false)
    private Boolean isSuccess;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 작업 이름·분류·대상, 메서드와 경로로 정한다 (AuditActionCatalog)
    @Column(name = "action", length = 50)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private AuditCategory category;

    @Column(name = "target", length = 100)
    private String target;

    @Builder
    public AuditLog(String email, String method, String path, String query, int status, String ip, long durationMs,
                    String action, AuditCategory category, String target) {
        this.email = email;
        this.method = method;
        this.path = path;
        this.query = query;
        this.status = status;
        this.isSuccess = status < 400;
        this.ip = ip;
        this.durationMs = durationMs;
        this.createdAt = LocalDateTime.now();
        this.action = action;
        this.category = category;
        this.target = target;
    }
}
