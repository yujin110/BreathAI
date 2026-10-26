package com.breathAI.ttobagi_server.domain.dashboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
// 업로드 파일 내용 오류 목록, 앞의 일부만 담고 전체 건수를 함께 준다
public class UploadErrorResponse {
    private int totalErrors;
    private List<ErrorItem> errors;

    @Getter
    @Builder
    public static class ErrorItem {
        // 엑셀은 엑셀 행 번호, CSV는 파일의 줄 번호 (머리글이 1), 파일 전체 문제면 null
        private Integer row;
        // 문제가 된 칸 이름, 행 전체 문제면 null
        private String column;
        private String reason;
    }
}
