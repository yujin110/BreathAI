package com.breathAI.ttobagi_server.domain.dashboard.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

// AI가 보낸 분석 범위 건수, 콜백 수신·DB 저장(json)·조회 응답에 그대로 쓴다
// 전체 = 대상 + 모드 제외, 대상 = 유효 문장 + 짧은 문장 제외, 유효 문장 = 군집화 + 소량 생략
@Getter
@NoArgsConstructor
@EqualsAndHashCode
public class AnalysisScope {
    private Integer totalLogCount;
    private Integer targetLogCount;
    private Integer excludedByModeCount;
    private Integer excludedByTextCount;
    private Integer eligibleLogCount;
    private Integer clusteredLogCount;
    private Integer deferredSmallInputCount;
    private Integer noiseLogCount;
    private Integer visualizedLogCount;
    private Integer faqClusterCount;
    // 분류 근거별 건수 (EXPLICIT_LOG_TYPE, KEYWORD_MATCH_PERCENT, AI_PERCENT, LEGACY_DEFAULT)
    private Map<String, Integer> classificationSourceCounts;
    private String keywordScope;
}
