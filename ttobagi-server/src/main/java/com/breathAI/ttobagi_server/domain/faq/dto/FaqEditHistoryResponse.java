package com.breathAI.ttobagi_server.domain.faq.dto;

import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory.EditType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// FAQ 변경 이력 조회 응답, 페이징 정보 포함
public class FaqEditHistoryResponse {
    private long totalCount;
    private List<HistoryItem> histories;
    private int totalPages;
    private int currentPage;
    private int size;

    @Getter
    @Builder
    // 변경 이력 단건
    public static class HistoryItem {
        private Long historyId;
        private Long faqId;
        private EditType editType;
        private Long analysisId;
        // 아직 버전으로 묶이지 않은 변경이면 null
        private Long versionId;
        private String beforeQuestion;
        private String beforeAnswer;
        private List<String> beforeKeywords;
        private List<String> beforeSynonyms;
        private String afterQuestion;
        private String afterAnswer;
        private List<String> afterKeywords;
        private List<String> afterSynonyms;
        private String editReason;
        private String editedBy;

        @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC"
        )
        private LocalDateTime createdAt;
    }
}
