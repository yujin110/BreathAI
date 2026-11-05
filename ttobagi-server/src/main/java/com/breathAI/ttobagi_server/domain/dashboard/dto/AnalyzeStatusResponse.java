package com.breathAI.ttobagi_server.domain.dashboard.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 분석 진행 상태 조회 응답
public class AnalyzeStatusResponse {
    private String uploadId;
    private Long analysisId;
    private String status;
    // 전체 진행률(%)은 AI가 주지 않아 항상 null, 세부 진행 정보는 progressDetail
    private Integer progress;
    private AnalysisProgressResponse progressDetail;
    private String message;
    // 내가 시작했고 아직 진행 중이면 true, 취소 버튼 표시용
    private boolean cancelable;

    @JsonFormat(
        shape = JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
        timezone = "UTC"
    )
    private LocalDateTime updatedAt;
}