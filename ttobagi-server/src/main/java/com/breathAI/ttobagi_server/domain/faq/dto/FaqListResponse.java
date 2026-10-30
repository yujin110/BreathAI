package com.breathAI.ttobagi_server.domain.faq.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
// FAQ 목록 조회 응답, 페이징 정보 포함
public class FaqListResponse {

    // 특정 버전을 조회한 경우에만 채워진다. 현재 운영 FAQ를 조회하면 null
    private Long versionId;
    private String versionName;

    private long totalCount;
    private List<FaqItem> faqList;
    private int totalPages;
    private int currentPage;
    private int size;

    @Getter
    @Builder
    // FAQ 단건
    public static class FaqItem {
        private Long faqId;
        private Integer qType;
        private String category;
        private String standardQuestion;
        private String answer;
        private List<String> keywords;
        private List<String> synonyms;

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate createdAt;

    }
}
