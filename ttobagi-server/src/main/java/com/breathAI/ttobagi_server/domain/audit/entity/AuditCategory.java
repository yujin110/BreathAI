package com.breathAI.ttobagi_server.domain.audit.entity;

// 감사로그 작업 분류
// 화면 기본 조회는 AUTH·ANALYSIS·FAQ·EXPORT(주요 작업)만, READ는 요청할 때만, AUTO는 보여 주지 않는다
public enum AuditCategory {
    AUTH,       // 계정·보안
    ANALYSIS,   // 분석 업로드·이력 삭제
    FAQ,        // FAQ·후보·버전 변경
    EXPORT,     // 데이터 반출 (FAQ 다운로드, 감사로그 조회)
    READ,       // 일반 조회
    AUTO        // 화면이 자동으로 부르는 요청 (진행 상태, 실시간 연결, AI 콜백, 토큰 재발급)
}
