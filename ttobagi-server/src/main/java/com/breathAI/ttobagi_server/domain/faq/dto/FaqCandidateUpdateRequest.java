package com.breathAI.ttobagi_server.domain.faq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
// FAQ 후보 초안 수정 요청, 승인 전에 내용만 저장한다
public class FaqCandidateUpdateRequest {

    // 카테고리 코드
    private Integer qType;

    @NotBlank(message = "질문은 필수 입력 값입니다.")
    private String standardQuestion;

    @NotBlank(message = "답변 초안은 필수 입력 값입니다.")
    private String answerDraft;

    // 대표 키워드, 생략하면 기존 값을 유지한다
    private List<String> keywords;

    // 유사어, 보내면 그 목록으로 바꾸고 생략하면 기존 값을 유지한다 (후보 유사어 칸은 100자)
    private List<@Size(max = 100, message = "유사어는 100자 이하여야 합니다.") String> synonyms;
}
