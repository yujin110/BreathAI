package com.breathAI.ttobagi_server.domain.faq.entity;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;

import java.time.LocalDateTime;

// AI가 생성한 FAQ 후보
@Entity
@Getter
@Table(name = "gold_faq_candidate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqCandidate {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "candidate_id", columnDefinition = "BIGINT COMMENT '후보 식별 ID'")
    private Long candidateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", nullable = false, foreignKey = @ForeignKey(name = "fk_candidate_analysis_id"))
    private AnalysisJob analysisJob;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cluster_id", foreignKey = @ForeignKey(name = "fk_candidate_cluster_id"))
    private Cluster cluster;

    @Column(name = "candidate_type", nullable = false, length = 20, columnDefinition = "VARCHAR(20) COMMENT '신규(NEW) 또는 확장(EXPAND)'")
    @Enumerated(EnumType.STRING)
    private CandidateType candidateType;

    @Column(name = "standard_question", nullable = false, columnDefinition = "TEXT COMMENT '생성된 표준 질문'")
    private String standardQuestion;

    @Column(name = "answer_draft", columnDefinition = "TEXT COMMENT '생성된 답변 초안'")
    private String answerDraft;

    @Column(name = "q_type", columnDefinition = "INT COMMENT 'counselling_info 카테고리 번호; 미매칭 시 null'")
    private Integer qType;

    @Column(name = "category", length = 100, columnDefinition = "VARCHAR(100) COMMENT '일반상담 카테고리; 미매칭 시 null'")
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "similar_questions", columnDefinition = "json COMMENT '클러스터 내 실제 사용자 질문'")
    private List<String> similarQuestions;

    @Column(name = "review_status", nullable = false, length = 20, 
            columnDefinition = "VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, REJECTED, APPLIED'")
    @Enumerated(EnumType.STRING)
    private ReviewStatus reviewStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by", foreignKey = @ForeignKey(name = "fk_candidate_user_id"))
    private User reviewedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "occurrence_count", columnDefinition = "INT DEFAULT 0 COMMENT '유사 질문 출현 횟수'")
    private Integer occurrenceCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "representative_keywords", columnDefinition = "json COMMENT '대표 키워드 배열'")
    private List<String> representativeKeywords;

    public enum CandidateType { NEW, EXPAND }

    public enum ReviewStatus { PENDING, ACCEPTED, REJECTED, APPLIED }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (reviewStatus == null) reviewStatus = ReviewStatus.PENDING;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Builder
    public FaqCandidate(AnalysisJob analysisJob, Cluster cluster, 
                        CandidateType candidateType, String standardQuestion,
                        String answerDraft, Integer qType, String category,
                        List<String> similarQuestions, Integer occurrenceCount,
                        List<String> representativeKeywords) { 
        this.analysisJob = analysisJob;
        this.cluster = cluster;
        this.candidateType = candidateType;
        this.standardQuestion = standardQuestion;
        this.answerDraft = answerDraft;
        this.qType = qType;
        this.category = category;
        this.similarQuestions = similarQuestions;
        this.occurrenceCount = occurrenceCount;
        this.representativeKeywords = representativeKeywords; 
        this.reviewStatus = ReviewStatus.PENDING;
}

    // 후보 승인, PENDING 상태에서만 가능
    public void accept(User reviewer) {
        validatePendingStatus();
        this.reviewStatus = ReviewStatus.ACCEPTED;
        this.reviewedBy = reviewer;
    }

    // 후보 반려, PENDING 상태에서만 가능
    public void reject(User reviewer) {
        validatePendingStatus();
        this.reviewStatus = ReviewStatus.REJECTED;
        this.reviewedBy = reviewer;
    }

    // 검토 가능 상태인지 확인
    private void validatePendingStatus() {
        if (this.reviewStatus == ReviewStatus.APPLIED) {
            throw new IllegalStateException("이미 챗봇에 반영된 항목은 수정할 수 없습니다.");
        }
        if (this.reviewStatus != ReviewStatus.PENDING) {
            throw new IllegalStateException("이미 검토 완료된 항목입니다. 현재 상태: " + this.reviewStatus);
        }
    }

    // 승인 전 초안 수정, 호출 전에 대기 상태인지 확인한다
    public void updateDraft(String standardQuestion, String answerDraft,
                            List<String> representativeKeywords, Integer qType, String category) {
        this.standardQuestion = standardQuestion;
        this.answerDraft = answerDraft;
        if (representativeKeywords != null) this.representativeKeywords = representativeKeywords;
        this.qType = qType;
        this.category = category;
    }

    // 버전 복원으로 반영이 취소되면 다시 검토할 수 있게 대기 상태로 돌린다
    public void revertToPending() {
        this.reviewStatus = ReviewStatus.PENDING;
        this.reviewedBy = null;
    }

    // 운영 FAQ 반영 처리, ACCEPTED 상태에서만 가능
    public void apply() {
        if (this.reviewStatus != ReviewStatus.ACCEPTED) {
            throw new IllegalStateException("승인(ACCEPTED) 상태인 항목만 적용할 수 있습니다.");
        }
        this.reviewStatus = ReviewStatus.APPLIED;
    }

}
