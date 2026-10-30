package com.breathAI.ttobagi_server.domain.faq.entity;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

// 운영 FAQ 변경 이력 (후보 반영, 직접 수정, 삭제)
@Entity
@Getter
@Table(name = "gold_faq_edit_history", comment = "현행 FAQ 직접 수정 이력")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqEditHistory {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "faq_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_edit_history_faq_id"))
    private Faq faq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", foreignKey = @ForeignKey(name = "fk_edit_history_analysis_id"),
                columnDefinition = "BIGINT COMMENT '연관 분석 ID (수동 수정 시 NULL 가능)'")
    private AnalysisJob analysisJob;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edited_by", foreignKey = @ForeignKey(name = "fk_edit_history_user_id"),
                columnDefinition = "BIGINT COMMENT '수정한 관리자 ID (탈퇴 시 NULL)'")
    private User editedBy;

    // 이 변경이 묶인 버전, 아직 버전으로 확정되지 않았으면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", foreignKey = @ForeignKey(name = "fk_edit_history_version_id"),
                columnDefinition = "BIGINT COMMENT '이 변경이 포함된 버전 ID (미확정 시 NULL)'")
    private FaqVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "edit_type", nullable = false, length = 20,
            columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'CREATE, EXPAND, MANUAL, DELETE'")
    private EditType editType;

    @Column(name = "before_question", columnDefinition = "TEXT COMMENT '수정 전 질문'")
    private String beforeQuestion;

    @Column(name = "before_answer", columnDefinition = "LONGTEXT COMMENT '수정 전 답변'")
    private String beforeAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_keywords", columnDefinition = "json COMMENT '수정 전 키워드'")
    private List<String> beforeKeywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_synonyms", columnDefinition = "json COMMENT '수정 전 유사어'")
    private List<String> beforeSynonyms;

    @Column(name = "after_question", columnDefinition = "TEXT COMMENT '수정 후 질문'")
    private String afterQuestion;

    @Column(name = "after_answer", columnDefinition = "LONGTEXT COMMENT '수정 후 답변'")
    private String afterAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_keywords", columnDefinition = "json COMMENT '수정 후 키워드'")
    private List<String> afterKeywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_synonyms", columnDefinition = "json COMMENT '수정 후 유사어'")
    private List<String> afterSynonyms;

    @Column(name = "edit_reason", columnDefinition = "TEXT COMMENT '수정 사유'")
    private String editReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    // CREATE: NEW 후보 반영으로 신규 등록, EXPAND: EXPAND 후보 반영
    // MANUAL: 운영자 직접 수정, DELETE: 운영자 삭제
    public enum EditType { CREATE, EXPAND, MANUAL, DELETE }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Builder
    public FaqEditHistory(Faq faq, AnalysisJob analysisJob, User editedBy, EditType editType,
                        String beforeQuestion, String beforeAnswer, List<String> beforeKeywords,
                        List<String> beforeSynonyms,
                        String afterQuestion, String afterAnswer, List<String> afterKeywords,
                        List<String> afterSynonyms,
                        String editReason) {
        this.faq = faq;
        this.analysisJob = analysisJob;
        this.editedBy = editedBy;
        this.editType = editType;
        this.beforeQuestion = beforeQuestion;
        this.beforeAnswer = beforeAnswer;
        this.beforeKeywords = beforeKeywords;
        this.beforeSynonyms = beforeSynonyms;
        this.afterQuestion = afterQuestion;
        this.afterAnswer = afterAnswer;
        this.afterKeywords = afterKeywords;
        this.afterSynonyms = afterSynonyms;
        this.editReason = editReason;
    }

}
