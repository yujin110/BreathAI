package com.breathAI.ttobagi_server.domain.faq.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
// FAQ 후보 반려 요청, 사유는 선택
public class FaqCandidateRejectRequest {

    private String reason;
}
