package com.breathAI.ttobagi_server.domain.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// 분석 세부 진행 정보, 상태 조회의 progressDetail과 SSE progress 이벤트에 쓴다
@Getter
@Builder
public class AnalysisProgressResponse {
    private String phase;
    // 현재 단계 기준 건수, 모르면 null
    private Integer completedUnits;
    private Integer totalUnits;
    private String unit;
    private Double elapsedSeconds;
    private Long sequence;

    // 진행 정보가 마지막으로 바뀐 시각 (BE 기준)
    @JsonFormat(
        shape = JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
        timezone = "UTC"
    )
    private LocalDateTime updatedAt;

    // 진행 중인데 60초 넘게 바뀌지 않음, 실패라는 뜻은 아니다
    private boolean delayed;
}
