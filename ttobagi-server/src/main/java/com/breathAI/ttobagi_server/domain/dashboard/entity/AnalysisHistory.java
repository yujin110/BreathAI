package com.breathAI.ttobagi_server.domain.dashboard.entity;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 사용자별 분석 요청 이력, 삭제해도 분석 데이터는 남는다
@Entity
@Getter
@Table(
    name = "gold_analysis_history",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_history_user_analysis", columnNames = {"user_id", "analysis_id"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_history_user_id"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id", nullable = false, foreignKey = @ForeignKey(name = "fk_history_analysis_id"))
    private AnalysisJob analysisJob;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Builder
    public AnalysisHistory(User user, AnalysisJob analysisJob) {
        this.user = user;
        this.analysisJob = analysisJob;
        this.requestedAt = LocalDateTime.now();
    }

    // 같은 분석을 다시 요청하면 목록 맨 위로 올린다
    public void touch() {
        this.requestedAt = LocalDateTime.now();
    }
}
