package com.breathAI.ttobagi_server.domain.faq.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
// FAQ 단건 상세 조회 응답
public class FaqDetailResponse {
    private Long faqId;
    private Integer qType;
    private String category;
    private String standardQuestion;
    private String answer;
    private List<String> keywords;
    private List<String> synonyms;
    private Integer qaCnt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate updatedAt;
}
