package com.breathAI.ttobagi_server.domain.dashboard.entity;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicInsert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

// 데이터 분석 작업 이력 및 상태
@Entity
@Getter
@DynamicInsert
@Table(
    name = "gold_analysis_job",
    comment = "데이터 분석 작업 이력 및 상태"
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long analysisId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_id", nullable = false, foreignKey = @ForeignKey(name = "fk_analysis_upload_id"))
    private UploadFile uploadFile;

    // 이 분석을 시작한 사람, 취소 권한 판단에 쓴다. 예전 분석과 탈퇴한 사람은 비어 있다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", nullable = true, foreignKey = @ForeignKey(name = "fk_analysis_requested_by"))
    private User requestedBy;

    @Column(name = "status", nullable = false, length = 20, 
            columnDefinition = "VARCHAR(20) COMMENT 'PENDING, PREPROCESSING, ANALYZING, COMPLETED, FAIL, CANCELED'")
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "error_message", columnDefinition = "TEXT COMMENT '실패 시 에러 로그'")
    private String errorMessage;

    @Column(name = "started_at", columnDefinition = "DATETIME COMMENT '분석 시작 시각'")
    private LocalDateTime startedAt;

    @Column(name = "finished_at", columnDefinition = "DATETIME COMMENT '분석 종료 시각'")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime createdAt;

    @Column(name = "period_start_date", columnDefinition = "DATE COMMENT '분석 대상 시작일'")
    private LocalDate periodStartDate;

    @Column(name = "period_end_date", columnDefinition = "DATE COMMENT '분석 대상 종료일'")
    private LocalDate periodEndDate;

    @Column(name = "file_name", nullable = false, length = 255, columnDefinition = "VARCHAR(255) COMMENT '업로드 당시 파일명 참조'")
    private String fileName;

    @Column(name = "is_masking_enabled")
    @ColumnDefault("0")
    private Boolean isMaskingEnabled;

    @Column(name = "is_translation_enabled")
    @ColumnDefault("0")
    private Boolean isTranslationEnabled;

    // 군집화 설정, 비어 있으면 AI 서버 기본값을 썼다는 뜻이다
    @Column(name = "min_cluster_size")
    private Integer minClusterSize;

    @Column(name = "min_samples")
    private Integer minSamples;

    // AI가 보낸 업로드 파일별 요약값, 보내지 않은 예전 분석은 비어 있다
    @Column(name = "total_log_count")
    private Integer totalLogCount;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "low_quality_count")
    private Integer lowQualityCount;

    @Column(name = "unanswer_count")
    private Integer unanswerCount;

    @Column(name = "current_unanswer_rate", precision = 5, scale = 2)
    private BigDecimal currentUnanswerRate;

    @Column(name = "predicted_accuracy_gain", precision = 5, scale = 2)
    private BigDecimal predictedAccuracyGain;

    @Column(name = "resolved_count_by_ai")
    private Integer resolvedCountByAi;

    public enum Status {
        PENDING, PREPROCESSING, ANALYZING, COMPLETED, FAIL, CANCELED;

        // 완료·실패·취소는 더 바뀌지 않는다
        public boolean isFinished() {
            return this == COMPLETED || this == FAIL || this == CANCELED;
        }
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = Status.PENDING;
        if (this.isMaskingEnabled == null) this.isMaskingEnabled = false;
        if (this.isTranslationEnabled == null) this.isTranslationEnabled = false;
    }

    @Builder
    public AnalysisJob(UploadFile uploadFile, User requestedBy, String fileName,
                       Boolean isMaskingEnabled, Boolean isTranslationEnabled,
                       LocalDate periodStartDate, LocalDate periodEndDate,
                       Integer minClusterSize, Integer minSamples) {
        this.uploadFile = uploadFile;
        this.requestedBy = requestedBy;
        this.fileName = fileName;
        this.isMaskingEnabled = isMaskingEnabled != null ? isMaskingEnabled : false;
        this.isTranslationEnabled = isTranslationEnabled != null ? isTranslationEnabled : false;
        this.periodStartDate = periodStartDate;
        this.periodEndDate = periodEndDate;
        this.minClusterSize = minClusterSize;
        this.minSamples = minSamples;
        this.status = Status.PENDING;
    }

    // 결과에 영향을 주는 분석 옵션이 모두 같은지 확인, 같은 파일의 기존 결과를 재사용할 때 쓴다
    public boolean hasSameOptions(LocalDate periodStartDate, LocalDate periodEndDate,
                                  boolean isMaskingEnabled, boolean isTranslationEnabled,
                                  Integer minClusterSize, Integer minSamples) {
        return Objects.equals(this.periodStartDate, periodStartDate)
                && Objects.equals(this.periodEndDate, periodEndDate)
                && Boolean.TRUE.equals(this.isMaskingEnabled) == isMaskingEnabled
                && Boolean.TRUE.equals(this.isTranslationEnabled) == isTranslationEnabled
                && Objects.equals(this.minClusterSize, minClusterSize)
                && Objects.equals(this.minSamples, minSamples);
    }

    // 전처리 단계 진입, 분석 시작 시각 기록
    public void startPreprocessing() {
        this.status = Status.PREPROCESSING;
        this.startedAt = LocalDateTime.now();
    }

    // AI 분석 단계 진입
    public void startAnalyzing() {
        this.status = Status.ANALYZING;
    }

    // 분석 완료 처리
    public void finish() {
        this.status = Status.COMPLETED;
        this.finishedAt = LocalDateTime.now();
    }

    // 분석 실패 처리, 사유 기록
    public void fail(String errorMessage) {
        this.status = Status.FAIL;
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }

    // 시작한 사람의 취소 처리, 이후 AI 콜백은 무시한다
    public void cancel() {
        this.status = Status.CANCELED;
        this.finishedAt = LocalDateTime.now();
    }

    // 분석을 시작한 사람인지 확인, 비어 있으면 아무도 아니다
    public boolean isRequestedBy(User user) {
        return requestedBy != null && user != null && Objects.equals(requestedBy.getUserId(), user.getUserId());
    }

    // AI가 보낸 파일별 요약값 기록, 다시 계산하지 않고 받은 값을 그대로 둔다
    public void recordSummary(Integer totalLogCount, Integer correctCount, Integer lowQualityCount,
                              Integer unanswerCount, BigDecimal currentUnanswerRate,
                              BigDecimal predictedAccuracyGain, Integer resolvedCountByAi) {
        this.totalLogCount = totalLogCount;
        this.correctCount = correctCount;
        this.lowQualityCount = lowQualityCount;
        this.unanswerCount = unanswerCount;
        this.currentUnanswerRate = currentUnanswerRate;
        this.predictedAccuracyGain = predictedAccuracyGain;
        this.resolvedCountByAi = resolvedCountByAi;
    }

    public boolean hasSummary() {
        return totalLogCount != null;
    }

}
