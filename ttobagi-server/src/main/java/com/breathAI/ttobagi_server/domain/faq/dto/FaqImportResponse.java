package com.breathAI.ttobagi_server.domain.faq.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

// FAQ 일괄 업로드 결과
// 바뀐 것이 없으면 버전을 만들지 않아 versionId·versionName이 null이다
@Getter
@Builder
public class FaqImportResponse {
    private int addedCount;
    private int updatedCount;
    private int unchangedCount;
    // 같은 질문의 FAQ가 여러 개라 어느 것을 고칠지 정하지 못해 반영하지 않은 행 수
    private int skippedCount;
    private List<String> newCategories;
    private List<MergedDuplicate> mergedDuplicates;
    private List<SkippedRow> skipped;
    private Long versionId;
    private String versionName;
    private List<AddedItem> added;
    private List<UpdatedItem> updated;

    // 파일 안에서 질문과 내용이 모두 같아 한 건으로 반영한 행들
    @Getter
    @Builder
    public static class MergedDuplicate {
        private String standardQuestion;
        private List<Integer> rows;
    }

    @Getter
    @Builder
    public static class SkippedRow {
        private Integer row;
        private String standardQuestion;
        private List<Long> candidateFaqIds;
        private String reason;
    }

    @Getter
    @Builder
    public static class AddedItem {
        private Long faqId;
        private String standardQuestion;
    }

    @Getter
    @Builder
    public static class UpdatedItem {
        private Long faqId;
        private String standardQuestion;
        // answer, category, keywords, synonyms 중 바뀐 것
        private List<String> changedFields;
    }
}
