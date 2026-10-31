package com.breathAI.ttobagi_server.domain.faq.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.breathAI.ttobagi_server.domain.auth.entity.User;

import java.time.LocalDateTime;

// 실제 운영 FAQ
@Entity
@Getter
@Table(name = "gold_faq", comment = "실제 운영 FAQ")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Faq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "faq_id")
    private Long faqId;

    @Column(name = "source_seq_num", columnDefinition = "INT COMMENT 'bronze_counselling_info.seq_num 참조 (기존 FAQ 연동용)'")
    private Integer sourceSeqNum;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", foreignKey = @ForeignKey(name = "fk_faq_candidate_id"))
    private FaqCandidate candidate;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT COMMENT '질문'")
    private String question;

    @Column(name = "answer", nullable = false, columnDefinition = "LONGTEXT COMMENT '답변'")
    private String answer;

    @Column(name = "keywords", columnDefinition = "JSON COMMENT '[\"유실물\", \"분실물\"]'")
    private String keywords;

    @Column(name = "synonyms", columnDefinition = "JSON COMMENT '[\"분실물센터\", \"유실물센타\"]'")
    private String synonyms;

    @Column(name = "q_type", columnDefinition = "INT COMMENT '카테고리 코드'")
    private Integer qType;

    @Column(name = "category", length = 100, columnDefinition = "VARCHAR(100) COMMENT '일반상담 카테고리'")
    private String category;

    @Column(name = "qa_cnt", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 0 COMMENT '누적 질의 수'")
    @ColumnDefault("0")
    private Integer qaCnt;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "is_active", nullable = false, columnDefinition = "TINYINT NOT NULL DEFAULT 1 COMMENT '활성 여부'")
    @ColumnDefault("1")
    private Boolean isActive;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(name = "fk_faq_created_by"))
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.isActive == null) this.isActive = true;
        if (this.qaCnt == null) this.qaCnt = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Builder
    public Faq(Integer sourceSeqNum, FaqCandidate candidate, String question, String answer,
               String keywords, String synonyms, Integer qType, String category, User createdBy) {
        this.sourceSeqNum = sourceSeqNum;
        this.candidate = candidate;
        this.question = question;
        this.answer = answer;
        this.keywords = keywords;
        this.synonyms = synonyms;
        this.qType = qType;
        this.category = category;
        this.createdBy = createdBy;
        this.isActive = true;
        this.qaCnt = 0;
    }

    // 질문, 답변, 키워드, 유사어, 카테고리 코드 수정
    // PATCH 요청 특성상 전달되지 않은 필드는 기존 값을 유지한다
    public void update(String question, String answer, String keywords, String synonyms, Integer qType) {
        if (question != null) this.question = question;
        if (answer != null) this.answer = answer;
        if (keywords != null) this.keywords = keywords;
        if (synonyms != null) this.synonyms = synonyms;
        if (qType != null) this.qType = qType;
    }

    // 카테고리 표시명 변경 (카테고리 코드가 바뀔 때 함께 맞춘다)
    public void changeCategory(String category) {
        this.category = category;
    }

    // 비활성 처리 (soft delete)
    public void deactivate() {
        this.isActive = false;
    }

    // 버전 사본의 값으로 되돌린다, 빈 값도 그대로 쓰고 삭제된 FAQ는 다시 활성화한다
    public void restoreFrom(String question, String answer, String keywords, String synonyms,
                            Integer qType, String category) {
        this.question = question;
        this.answer = answer;
        this.keywords = keywords;
        this.synonyms = synonyms;
        this.qType = qType;
        this.category = category;
        this.isActive = true;
    }
}
