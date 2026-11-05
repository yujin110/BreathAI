package com.breathAI.ttobagi_server.domain.dashboard.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

// AI가 보낸 분석 진행 정보, AI 응답 수신·DB 저장(json)에 그대로 쓴다
// 건수는 현재 단계 기준이며 모를 때는 둘 다 null이다
@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class AnalysisProgress {
    // QUEUED, CLASSIFYING, PREPROCESSING, EMBEDDING, DIMENSION_REDUCTION, CLUSTERING,
    // FAQ_GENERATION, RETRIEVING, ANSWER_PIPELINE, FINALIZING
    private String phase;
    private Integer completedUnits;
    private Integer totalUnits;
    // LOG, CLUSTER, STEP 또는 null
    private String unit;
    private Double elapsedSeconds;
    // AI 시각(UTC), 지연 판정에는 쓰지 않는다
    private String heartbeatAt;
    // 작업 안에서 계속 늘어나는 번호, 더 큰 값만 반영한다
    private Long sequence;
}
