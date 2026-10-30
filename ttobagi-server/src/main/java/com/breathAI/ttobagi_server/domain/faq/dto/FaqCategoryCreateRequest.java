package com.breathAI.ttobagi_server.domain.faq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

// FAQ 카테고리 생성 요청
@Getter
@NoArgsConstructor
public class FaqCategoryCreateRequest {

    @NotBlank(message = "카테고리 이름은 필수 입력 값입니다.")
    @Size(max = 100, message = "카테고리 이름은 100자 이하여야 합니다.")
    private String name;
}
