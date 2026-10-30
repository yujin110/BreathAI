package com.breathAI.ttobagi_server.domain.dashboard.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
// AI 서버 분석 콜백 요청, 진행 상태와 최종 분석 결과 수신
public class AnalysisCallbackRequest {

    private String status;
    private String errorMessage;
    private String message;
    private String updatedAt;

    private SystemStatusResult systemStatus;
    private PerformanceMetricsResult performanceMetrics;
    private List<TrendItemResult> trend;
    private List<BurstKeywordResult> burstKeywords;
    private UnanswerAnalysisResult unanswerAnalysis;
    private ClusteringViewResult clusteringView;
    private List<FaqCandidateResult> faqCandidates;
    private EvaluationResult evaluation;
    private List<SurgeDetectionResult> surgeDetection;

    private List<UsageStatResult> usageStats;

    @Getter
    @NoArgsConstructor
    public static class SystemStatusResult {
        private Integer totalLogCount;
        private SamplingStatusResult samplingStatus;

        @Getter
        @NoArgsConstructor
        public static class SamplingStatusResult {
            private Integer correct;
            private Integer lowQuality;
            private Integer unanswer;
        }
    }

    @Getter
    @NoArgsConstructor
    public static class PerformanceMetricsResult {
        private Double currentUnanswerRate;
        private Double predictedAccuracyGain;
        private Integer resolvedCountByAI;
    }

    @Getter
    @NoArgsConstructor
    public static class TrendItemResult {
        private String date;
        private Integer unanswerCount;
        private Integer lowQualityCount;
    }

    @Getter
    @NoArgsConstructor
    public static class BurstKeywordResult {
        private String keyword;
        private Double increasedRate;
        private Integer count;
    }

    @Getter
    @NoArgsConstructor
    public static class UnanswerAnalysisResult {
        private List<ReasonItemResult> byReason;

        @Getter
        @NoArgsConstructor
        public static class ReasonItemResult {
            private String reason;
            private Integer count;
        }
    }

    @Getter
    @NoArgsConstructor
    public static class ClusteringViewResult {
        private List<PointResult> points;
        private List<ClusterNameResult> clusterNames;

        @Getter
        @NoArgsConstructor
        public static class PointResult {
            private Double x;
            private Double y;
            private Integer clusterLabel;
            private String text;
            private Integer sourceLogSeqNum;
            private String logType;
        }

        @Getter
        @NoArgsConstructor
        public static class ClusterNameResult {
            private Integer clusterLabel;
            private String name;
            private List<String> topKeywords;
            private Integer size;
        }
    }

    @Getter
    @NoArgsConstructor
    public static class EvaluationResult {
        private String beforeStatDate;
        private Integer afterUnanswerCount;
        private Double afterUnanswerRate;
        private Double accuracyGain;
        private Integer resolvedCountByAI;
        private Double falsePositiveRate;
        private Double evalThreshold;
    }

    // 기존 유지
    @Getter
    @NoArgsConstructor
    public static class FaqCandidateResult {
        private Long candidateId;
        private String candidateType;
        private String standardQuestion;
        private String answerDraft;
        private Integer qType;
        private String category;
        private List<String> similarQuestions;
        private Integer occurrenceCount;
        private List<String> representativeKeywords;
        private Integer clusterLabel;
        private List<SynonymResult> synonyms;
        private List<MatchedFaqResult> matchedFaqs;
    }

    @Getter
    @NoArgsConstructor
    public static class SynonymResult {
        private String text;
        private String type;
    }

    @Getter
    @NoArgsConstructor
    public static class MatchedFaqResult {
        // gold_faq.faq_id, 운영 FAQ가 아닌 자료와 매칭되면 null (예전 형식은 보내지 않음)
        private Long matchedFaqId;
        private Integer matchedFaqSeqNum;
        private Double matchScore;
    }

    @Getter
    @NoArgsConstructor
    public static class SurgeDetectionResult {
        private String keyword;
        private Boolean isNew;
        private Double increasedRate;
        private Integer keywordCount;
        private Integer relatedClusterLabel;
        private List<SurgeStatResult> periodStats; 

        @Getter
        @NoArgsConstructor
        public static class SurgeStatResult {
            private String statDate;
            private String periodType;
            private Integer count;
        }
    }

    @Getter
    @NoArgsConstructor
    public static class UsageStatResult {
        private String statDate;
        private Integer totalCnt;
        private Integer answerCnt;
        private Integer lowQualityCount;
        private Integer unanswerCnt;
    }
}
