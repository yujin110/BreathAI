package com.breathAI.ttobagi_server.domain.dashboard.dto;

import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;

// 이력·대시보드 조회 범위, mine은 내가 요청한 분석만, all은 전체
public enum HistoryScope {
    MINE, ALL;

    // 요청 값은 mine, all로 받으며 대소문자는 구분하지 않는다
    public static HistoryScope from(String value) {
        for (HistoryScope scope : values()) {
            if (scope.name().equalsIgnoreCase(value)) return scope;
        }
        throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
    }
}
