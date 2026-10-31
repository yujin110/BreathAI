package com.breathAI.ttobagi_server.domain.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 기간 기반 분석 결과 응답, 대시보드 전체 지표 포함
public class AnalyzeResultResponse {
    private String periodStartDate;
    private String periodEndDate;
    private SystemStatus systemStatus;
    private PerformanceMetrics performanceMetrics;
    private List<TrendItem> trend;
    private List<UsageStatItem> usageStats;
    private List<BurstKeyword> burstKeywords;
    private UnansweredAnalysis unanswerAnalysis;
    private ClusteringView clusteringView;
    private List<FaqCandidate> faqCandidates;
    private Evaluation evaluation;
    private List<SurgeDetection> surgeDetection;

    @Getter
    @Builder
    public static class SystemStatus {
        private int totalLogCount;
        private SamplingStatus samplingStatus;

        @Getter
        @Builder
        public static class SamplingStatus {
            private int correct;
            private int lowQuality;
            private int unanswer;
        }
    }

    @Getter
    @Builder
    public static class PerformanceMetrics {
        private double currentUnanswerRate;
        private double predictedAccuracyGain;
        private int resolvedCountByAI;
    }

    @Getter
    @Builder
    public static class TrendItem {
        private String date;
        private int unanswerCount;
        private int lowQualityCount;
    }

    // 업로드 파일의 날짜별 건수 (AI 콜백의 usageStats 형식), 전체·정상 건수는 예전 분석이면 null
    @Getter
    @Builder
    public static class UsageStatItem {
        private String statDate;
        private Integer totalCnt;
        private Integer answerCnt;
        private int lowQualityCount;
        private int unanswerCnt;
    }

    @Getter
    @Builder
    public static class BurstKeyword {
        private String keyword;
        private double increasedRate;
        private int count;
    }

    @Getter
    @Builder
    public static class UnansweredAnalysis {
        private List<ReasonItem> byReason;

        @Getter
        @Builder
        public static class ReasonItem {
            private String reason;
            private int count;
        }
    }

    @Getter
    @Builder
    public static class ClusteringView {
        private List<Point> points;
        private List<ClusterName> clusterNames;

        @Getter
        @Builder
        public static class Point {
            private double x;
            private double y;
            private int clusterLabel;
            private String text;
            private Integer sourceLogSeqNum;
            private String logType;
        }

        @Getter
        @Builder
        public static class ClusterName {
            private Long clusterId;
            private int clusterLabel;
            private String name;
            private List<String> topKeywords;
            private int size;
        }
    }

    @Getter
    @Builder
    public static class FaqCandidate {
        private Long candidateId;
        private int clusterLabel;
        private String candidateType;
        private Integer qType;
        private String category;
        private String standardQuestion;
        private List<String> similarQuestions;
        private List<String> representativeKeywords;
        private String answerDraft;
        private List<SynonymItem> synonyms;
        private int occurrenceCount;
        private List<MatchedFaq> matchedFaqs;

        @Getter
        @Builder
        public static class SynonymItem {
            private String text;
            private String type;
        }

        @Getter
        @Builder
        public static class MatchedFaq {
            private Long matchedFaqId;
            private Integer matchedFaqSeqNum;
            private double matchScore;
        }
    }

    @Getter
    @Builder
    public static class Evaluation {
        private String beforeStatDate;
        private int afterUnanswerCount;
        private double afterUnanswerRate;
        private double accuracyGain;
        private int resolvedCountByAI;
        private double falsePositiveRate;
        private double evalThreshold;
    }

    @Getter
    @Builder
    public static class SurgeDetection {
        private String keyword;
        // boolean이면 응답에 new로 나가므로 Boolean으로 둔다
        @JsonProperty("isNew")
        private Boolean isNew;
        private int relatedClusterLabel;
        private List<PeriodStat> periodStats;

        @Getter
        @Builder
        public static class PeriodStat {
            private String statDate;
            private String periodType;
            private int count;
        }
    }
}
