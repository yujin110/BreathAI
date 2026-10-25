package com.breathAI.ttobagi_server.domain.faq.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
// FAQ 카테고리 목록 응답, 카테고리 필터 선택지용
public class FaqCategoryListResponse {
    private List<CategoryItem> categories;

    @Getter
    @Builder
    public static class CategoryItem {
        private Integer qType;
        private String name;
    }
}
