package com.breathAI.ttobagi_server.domain.faq.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
// FAQ 직접 등록 요청
public class FaqCreateRequest {

    // 카테고리 코드, 카테고리 이름은 마스터에서 찾는다
    private Integer qType;

    @NotBlank(message = "질문은 필수 입력 값입니다.")
    private String standardQuestion;

    @NotBlank(message = "답변은 필수 입력 값입니다.")
    private String answer;

    private List<String> keywords;

    private List<String> synonyms;
}
