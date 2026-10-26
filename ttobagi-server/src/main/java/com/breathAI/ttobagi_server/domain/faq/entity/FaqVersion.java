package com.breathAI.ttobagi_server.domain.faq.entity;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

// FAQ 버전
// 엑셀 업로드·다운로드 시점에 만들어지며, 직전 버전 이후의 변경 이력을 묶는다
@Entity
@Getter
@Table(name = "gold_faq_version", comment = "FAQ 버전")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version_id")
    private Long versionId;

    @Column(name = "version_name", nullable = false, length = 100,
            columnDefinition = "VARCHAR(100) NOT NULL COMMENT '버전 이름 (v번호_날짜)'")
    private String versionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "version_type", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL COMMENT 'UPLOAD, DOWNLOAD'")
    private VersionType versionType;

    @Column(name = "total_faq_count", nullable = false,
            columnDefinition = "INT NOT NULL COMMENT '버전 생성 시점의 활성 FAQ 수'")
    private Integer totalFaqCount;

    @Column(name = "created_count", nullable = false,
            columnDefinition = "INT NOT NULL DEFAULT 0 COMMENT '신규 등록 건수'")
    private Integer createdCount;

    @Column(name = "expanded_count", nullable = false,
            columnDefinition = "INT NOT NULL DEFAULT 0 COMMENT '확장 건수'")
    private Integer expandedCount;

    @Column(name = "manual_count", nullable = false,
            columnDefinition = "INT NOT NULL DEFAULT 0 COMMENT '직접 수정 건수'")
    private Integer manualCount;

    @Column(name = "deleted_count", nullable = false,
            columnDefinition = "INT NOT NULL DEFAULT 0 COMMENT '삭제 건수'")
    private Integer deletedCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_version_id", foreignKey = @ForeignKey(name = "fk_faq_version_previous_id"),
                columnDefinition = "BIGINT COMMENT '직전 버전 ID'")
    private FaqVersion previousVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_faq_version_created_by"),
                columnDefinition = "BIGINT COMMENT '버전을 만든 사용자 ID (탈퇴 시 NULL)'")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // MANUAL은 관리자가 직접 만든 버전, RESTORE는 이전 버전으로 복원한 직후의 버전
    public enum VersionType { UPLOAD, DOWNLOAD, MANUAL, RESTORE }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Builder
    public FaqVersion(String versionName, VersionType versionType, Integer totalFaqCount,
                      Integer createdCount, Integer expandedCount, Integer manualCount, Integer deletedCount,
                      FaqVersion previousVersion, User createdBy) {
        this.versionName = versionName;
        this.versionType = versionType;
        this.totalFaqCount = totalFaqCount;
        this.createdCount = createdCount;
        this.expandedCount = expandedCount;
        this.manualCount = manualCount;
        this.deletedCount = deletedCount;
        this.previousVersion = previousVersion;
        this.createdBy = createdBy;
    }

    // 버전 번호가 정해진 뒤 이름을 붙인다
    public void rename(String versionName) {
        this.versionName = versionName;
    }
}
