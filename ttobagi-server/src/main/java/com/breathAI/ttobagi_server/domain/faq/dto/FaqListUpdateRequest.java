package com.breathAI.ttobagi_server.domain.faq.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
// FAQ 수정 요청
public class FaqListUpdateRequest {

    // 카테고리 코드
    private Integer qType;

    @NotBlank(message = "질문은 필수 입력 값입니다.")
    private String standardQuestion;

    @NotBlank(message = "답변은 필수 입력 값입니다.")
    private String answer;

    private List<String> keywords;

    private List<String> synonyms;

    private String editReason;

}
