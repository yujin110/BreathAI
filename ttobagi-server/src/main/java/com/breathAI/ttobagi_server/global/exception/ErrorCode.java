package com.breathAI.ttobagi_server.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 서비스 전역 에러 코드 목록
// 접두어: G 공통 / A 인증 / U 사용자 / D 대시보드 / F FAQ / S 서버
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 400 BAD REQUEST: 잘못된 요청
    INVALID_INPUT_VALUE(400, "G-001", "입력값이 올바르지 않습니다."),
    INVALID_UPLOAD_FILE(400, "D-004", "비어 있거나 읽을 수 없는 파일입니다."),
    UNSUPPORTED_UPLOAD_FORMAT(400, "D-005", "CSV 또는 XLSX 파일만 업로드할 수 있습니다."),
    INVALID_UPLOAD_ROWS(400, "D-006", "파일 내용에 오류가 있습니다. 오류 목록을 확인해 주세요."),
    ALREADY_USED_TOKEN(400, "G-002", "이미 사용되었거나 만료된 토큰입니다."),
    INVALID_RESET_CODE(400, "A-008", "인증 코드가 일치하지 않습니다."),
    EXPIRED_RESET_CODE(400, "A-009", "인증 코드가 만료되었습니다. 코드를 다시 발급받아 주세요."),
    
    // 401 UNAUTHORIZED: 인증 실패
    INVALID_PASSWORD(401, "A-001", "비밀번호가 일치하지 않습니다."),
    INVALID_TOKEN(401, "A-002", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(401, "A-003", "토큰이 만료되었습니다."),
    LOGIN_REQUIRED(401, "A-004", "로그인이 필요합니다."),

    // 403 FORBIDDEN: 권한 없음
    INVALID_ADMIN_CODE(403, "A-005", "관리자 코드가 올바르지 않습니다."),
    ACCESS_DENIED(403, "A-006", "접근 권한이 없습니다."),

    // 404 NOT_FOUND: 리소스를 찾을 수 없음
    USER_NOT_FOUND(404, "U-001", "사용자를 찾을 수 없습니다."),
    TOKEN_NOT_FOUND(404, "A-007", "일치하는 토큰 정보를 찾을 수 없습니다."),
    ANALYSIS_NOT_FOUND(404, "D-001", "분석 결과를 찾을 수 없습니다."),
    UPLOAD_FILE_NOT_FOUND(404, "D-002", "업로드 파일을 찾을 수 없습니다."),
    USAGE_STAT_NOT_FOUND(404, "D-003", "사용량 통계를 찾을 수 없습니다."),
    
    // FAQ 관련
    FAQ_NOT_FOUND(404, "F-001", "해당 FAQ를 찾을 수 없습니다."),
    FAQ_CANDIDATE_NOT_FOUND(404, "F-002", "FAQ 후보를 찾을 수 없습니다."),
    FAQ_CANDIDATE_NOT_APPLICABLE(409, "F-003", "반영할 수 없는 상태의 FAQ 후보입니다."),
    FAQ_EXPAND_TARGET_NOT_FOUND(409, "F-004", "확장할 기존 FAQ를 찾을 수 없습니다."),
    FAQ_VERSION_NOT_FOUND(404, "F-005", "FAQ 버전을 찾을 수 없습니다."),
    FAQ_CANDIDATE_NOT_REVIEWABLE(409, "F-006", "검토할 수 없는 상태의 FAQ 후보입니다."),
    FAQ_CATEGORY_DUPLICATED(409, "F-007", "이미 있는 카테고리입니다."),

    // 409 CONFLICT: 중복된 리소스
    EMAIL_DUPLICATED(409, "U-002", "이미 등록된 이메일입니다."),

    // 429 TOO MANY REQUESTS: 시도 횟수 초과
    RESET_CODE_ATTEMPTS_EXCEEDED(429, "A-010", "인증 코드 입력 횟수를 초과했습니다. 코드를 다시 발급받아 주세요."),
    SIGNUP_CODE_REQUEST_TOO_SOON(429, "A-011", "인증 코드는 1분에 한 번만 받을 수 있습니다. 잠시 후 다시 시도해 주세요."),

    // 500 INTERNAL SERVER ERROR: 서버 에러
    INTERNAL_SERVER_ERROR(500, "S-001", "서버 내부 오류가 발생했습니다."),
    FILE_READ_ERROR(500, "FILE_001", "파일을 읽는 중 오류가 발생했습니다.");

    // 응답 HTTP 상태 코드
    private final int status;
    // 클라이언트 분기용 식별 코드
    private final String code;
    // 사용자 노출 메시지
    private final String message;
}
