package com.breathAI.ttobagi_server.global.exception;

import com.breathAI.ttobagi_server.domain.dashboard.dto.UploadErrorResponse;
import lombok.Getter;

// 업로드 파일 내용 오류, 응답 data에 행별 오류 목록을 담는다
@Getter
public class UploadValidationException extends CustomException {

    private final UploadErrorResponse report;

    public UploadValidationException(UploadErrorResponse report) {
        super(ErrorCode.INVALID_UPLOAD_ROWS);
        this.report = report;
    }
}
