package com.breathAI.ttobagi_server.domain.faq.entity;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

// BE에서 새로 만든 FAQ 카테고리
// 기존 카테고리(bronze_cate_info)와 합쳐 읽는다 (FaqCategorySql.MASTER)
@Entity
@Getter
@Table(
    name = "gold_faq_category",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_faq_category_name_key", columnNames = {"name_key"})
    },
    comment = "BE에서 만든 FAQ 카테고리"
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqCategory {

    @Id
    @Column(name = "q_type", columnDefinition = "INT COMMENT '카테고리 코드 (100000부터)'")
    private Integer qType;

    @Column(name = "name", nullable = false, length = 100,
            columnDefinition = "VARCHAR(100) NOT NULL COMMENT '화면 표시명 (앞뒤 공백 제거, 연속 공백은 하나로)'")
    private String name;

    @Column(name = "name_key", nullable = false, length = 100,
            columnDefinition = "VARCHAR(100) NOT NULL COMMENT '중복 확인용 이름 (공백 모두 제거)'")
    private String nameKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_faq_category_created_by"),
                columnDefinition = "BIGINT COMMENT '만든 사용자 ID (탈퇴 시 NULL)'")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Builder
    public FaqCategory(Integer qType, String name, String nameKey, User createdBy) {
        this.qType = qType;
        this.name = name;
        this.nameKey = nameKey;
        this.createdBy = createdBy;
    }
}
