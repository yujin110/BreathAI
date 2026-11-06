package com.breathAI.ttobagi_server.domain.dashboard.entity;

// 군집화·FAQ 생성 대상, 통계는 모드와 관계없이 전체 입력 기준이다
public enum AnalysisMode {
    // 미답변 + 오답변(LOW_QUALITY)
    IMPROVEMENT,
    // 미답변만
    UNANSWER_ONLY,
    // 전체 유형, 모드가 생기기 전 분석도 이 값으로 본다
    ALL;

    // AI가 보낸 값 해석, 모르는 값이면 null
    public static AnalysisMode from(String value) {
        if (value == null) return null;
        for (AnalysisMode mode : values()) {
            if (mode.name().equals(value)) return mode;
        }
        return null;
    }
}
