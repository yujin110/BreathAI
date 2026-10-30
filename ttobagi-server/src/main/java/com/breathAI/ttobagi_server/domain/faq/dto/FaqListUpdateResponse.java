package com.breathAI.ttobagi_server.domain.faq.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
// FAQ 수정 결과
public class FaqListUpdateResponse {
    private Long faqId;
    private Integer qType;
    private String question;
    private String answer;
    private List<String> keywords;
    private List<String> synonyms;
    private Long historyId;

    @JsonFormat(
        shape = JsonFormat.Shape.STRING,
        pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
        timezone = "UTC"
    )
    private LocalDateTime updatedAt;
}
