package com.breathAI.ttobagi_server.domain.faq.dto;

import com.breathAI.ttobagi_server.domain.faq.entity.FaqCandidate.CandidateType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqCandidate.ReviewStatus;
import com.breathAI.ttobagi_server.domain.faq.entity.SynonymCandidate.SynonymType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 분석 단위 FAQ 후보 추천 목록
public class FaqCandidateListResponse {
    private Long analysisId;
    private List<FaqCandidateItem> recommendations;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    // 후보 단건
    public static class FaqCandidateItem {
        private Long clusterId;
        private Integer clusterLabel;
        private Long candidateId;
        private CandidateType candidateType;
        private Integer qType;
        private String category;
        private String standardQuestion;
        private List<String> similarQuestions;
        private String answerDraft;
        private ReviewStatus reviewStatus;
        private List<String> representativeKeywords;
        private Integer occurrenceCount;
        private List<SynonymItem> synonyms;
        private List<MatchedFaqItem> matchedFaqs;
        private java.util.Map<String, Object> analysisProvenance;

        @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC"
        )
        private LocalDateTime createdAt;

        @Getter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        // 유사 FAQ 단건
        // 해당 FAQ가 운영 FAQ에 없으면 matchedFaqId와 question은 null
        public static class MatchedFaqItem {
            private Long matchedFaqId;
            private Integer matchedFaqSeqNum;
            private String question;
            private double matchScore;
        }

        @Getter
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        // 유사어 단건
        public static class SynonymItem {
            private String text;
            private SynonymType type;
        }
    }
}
