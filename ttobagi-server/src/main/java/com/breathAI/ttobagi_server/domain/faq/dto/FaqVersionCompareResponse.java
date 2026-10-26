package com.breathAI.ttobagi_server.domain.faq.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
// 두 FAQ 버전(또는 버전과 현재 운영 FAQ) 비교 결과
// 추가·삭제는 어떤 FAQ인지만, 변경은 바뀐 칸의 전후 값만 담는다 (그림이 든 긴 답변 때문에 응답이 커지지 않게)
public class FaqVersionCompareResponse {
    private Long baseVersionId;
    private String baseVersionName;

    // 비교 대상 버전, 둘 다 null이면 현재 운영 FAQ와 비교한 것이다
    private Long targetVersionId;
    private String targetVersionName;

    private int addedCount;
    private int deletedCount;
    private int changedCount;

    private List<FaqItem> added;
    private List<FaqItem> deleted;
    private List<ChangedItem> changed;

    @Getter
    @Builder
    public static class FaqItem {
        private Long faqId;
        private Integer qType;
        private String category;
        private String standardQuestion;
    }

    @Getter
    @Builder
    public static class ChangedItem {
        private Long faqId;
        // 비교 대상 쪽 질문, 어떤 FAQ인지 보여 주는 용도
        private String standardQuestion;
        private List<FieldChange> changes;
    }

    @Getter
    @Builder
    // field는 standardQuestion, answer, keywords, qType, category 중 하나
    public static class FieldChange {
        private String field;
        private Object before;
        private Object after;
    }
}
