package com.breathAI.ttobagi_server.domain.dashboard.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 분석 이력 목록 응답
public class AnalyzeHistoryResponse {
    private List<HistoryItem> history;
    private long totalCount;
    private int totalPages;
    private int currentPage;

    @Getter
    @Builder
    public static class HistoryItem {
        private Long analysisId;
        private String fileName;
        private String status;
        private String period;

        @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC"
        )
        private LocalDateTime submittedAt;

        // 이 이력을 남긴 사람과 마지막 요청 시각, 같은 분석도 요청한 사람마다 한 줄씩 나온다
        private String requestedBy;

        @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC"
        )
        private LocalDateTime requestedAt;

        // 내 이력인지, 삭제 버튼 표시용
        private Boolean isMine;

        // 분석 옵션, 군집화 값이 null이면 AI 서버 기본값을 썼다는 뜻이다
        private Boolean isMaskingEnabled;
        private Boolean isTranslationEnabled;
        private Integer minClusterSize;
        private Integer minSamples;
    }
}
