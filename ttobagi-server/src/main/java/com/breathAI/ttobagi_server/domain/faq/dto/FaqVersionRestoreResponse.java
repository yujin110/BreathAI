package com.breathAI.ttobagi_server.domain.faq.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
// FAQ 버전 복원 결과, 바뀐 것이 없으면 새 버전은 만들지 않아 newVersionId가 null이다
public class FaqVersionRestoreResponse {
    private Long restoredVersionId;
    private String restoredVersionName;
    private Long newVersionId;
    private String newVersionName;

    // 사본 내용으로 되돌린 FAQ, 사본에 없어 삭제한 FAQ, 다시 살린 FAQ 수
    private int updatedCount;
    private int deletedCount;
    private int reactivatedCount;

    // 반영이 취소되어 대기 상태로 돌아간 FAQ 후보 수
    private int revertedCandidateCount;
}
