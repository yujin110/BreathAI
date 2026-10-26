package com.breathAI.ttobagi_server.global.exception;

import com.breathAI.ttobagi_server.domain.dashboard.dto.UploadErrorResponse;
import com.breathAI.ttobagi_server.global.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

// 컨트롤러 예외를 공통 응답 포맷으로 변환하는 전역 핸들러
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    // 의도적으로 던진 비즈니스 예외 처리
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handlerCustomException(CustomException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("CustomException: {}", errorCode.getMessage());
        // SSE처럼 JSON이 아닌 응답을 요청한 경우에도 오류는 JSON으로 내려준다
        return ResponseEntity
                .status(errorCode.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(errorCode));
    }

    // 업로드 파일 내용 오류 처리, data에 행별 오류 목록을 담는다
    @ExceptionHandler(UploadValidationException.class)
    public ResponseEntity<ApiResponse<UploadErrorResponse>> handleUploadValidation(UploadValidationException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("업로드 파일 내용 오류: {}건", ex.getReport().getTotalErrors());
        return ResponseEntity
                .status(errorCode.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiResponse<>(false, errorCode.getMessage(), ex.getReport(), errorCode.getStatus()));
    }

    // @Valid 검증 실패 처리 (필드별 사유 반환)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("Validation failed: {}", errors);

        return ResponseEntity.badRequest()
                    .body(new ApiResponse<> (false, "입력값 검증에 실패했습니다.", errors, 400));
    }

    // 본문 파싱 실패 처리 (잘못된 JSON, 깨진 인코딩 등)
    // 클라이언트 요청 오류이므로 500이 아닌 400으로 응답한다
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("요청 본문 파싱 실패: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("요청 본문을 읽을 수 없습니다.", 400));
    }

    // 파라미터·경로 변수의 형식 불일치 처리 (숫자 자리에 글자, 잘못된 날짜, 없는 enum 값 등)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("요청 값 형식 불일치: {}={}", ex.getName(), ex.getValue());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error("요청 값의 형식이 올바르지 않습니다.", 400));
    }

    // 스프링이 던지는 요청 오류 처리 (없는 경로, 허용되지 않은 메서드, 필수 값 누락 등)
    // 예외가 가진 상태 코드를 그대로 쓰고 응답 형식만 공통 포맷으로 맞춘다
    @ExceptionHandler({
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MaxUploadSizeExceededException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleRequestError(Exception ex) {
        int status = ex instanceof ErrorResponse response
                ? response.getStatusCode().value()
                : HttpStatus.BAD_REQUEST.value();
        String message = switch (status) {
            case 404 -> "요청한 경로를 찾을 수 없습니다.";
            case 405 -> "허용되지 않은 요청 방식입니다.";
            case 413 -> "업로드할 수 있는 파일 크기를 초과했습니다.";
            case 415 -> "지원하지 않는 요청 형식입니다.";
            default -> "필수 요청 값이 누락되었습니다.";
        };
        log.warn("잘못된 요청: status={}, {}", status, ex.getMessage());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(message, status));
    }

    // @PreAuthorize 권한 부족 처리
    // 아래의 Exception 처리에 잡혀 500이 되지 않도록 403으로 응답한다
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("권한 부족: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error("ADMIN 계정만 접근 가능합니다.", 403));
    }

    // SSE 연결이 제한 시간에 도달해 끝나는 것은 정상 동작이므로 오류로 기록하지 않는다
    // 응답이 이미 이벤트 스트림으로 전송 중이라 본문은 쓰지 않는다
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public void handleAsyncRequestTimeout() {
        log.debug("비동기 요청이 제한 시간에 도달해 종료되었습니다.");
    }

    // 미처리 예외의 최후 방어선 (내부 정보 비노출)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenereicException(Exception ex) {
        log.error("Unexpected error occurred: ", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("서버 내부 오류가 발생했습니다.", 500));
    }
}
