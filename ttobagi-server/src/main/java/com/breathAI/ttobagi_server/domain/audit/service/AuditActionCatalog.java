package com.breathAI.ttobagi_server.domain.audit.service;

import com.breathAI.ttobagi_server.domain.audit.entity.AuditCategory;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

import static com.breathAI.ttobagi_server.domain.audit.entity.AuditCategory.*;

// API 요청(메서드 + 경로)을 감사로그의 작업 이름·분류·대상으로 바꾸는 표
// API를 추가하면 여기에도 한 줄 추가한다. 표에 없으면 "기타 요청"으로 남고, GET은 조회(READ), 그 외는 분류 없이(주요 작업으로 보임) 남는다
public final class AuditActionCatalog {

    public record Resolved(String action, AuditCategory category, String target) {}

    // target은 경로 변수 이름, targetLabel은 대상 앞에 붙일 말 (예: faqId → "FAQ #123")
    private record Entry(String method, PathPattern pattern, String action, AuditCategory category,
                         String target, String targetLabel) {}

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<Entry> ENTRIES = List.of(
            // 계정·보안
            entry("POST", "/api/v1/auth/signup/code", "가입 인증 코드 요청", AUTH),
            entry("POST", "/api/v1/auth/signup", "회원가입", AUTH),
            entry("POST", "/api/v1/auth/login", "로그인", AUTH),
            entry("POST", "/api/v1/auth/logout", "로그아웃", AUTH),
            entry("POST", "/api/v1/auth/password/reset", "비밀번호 재설정 요청", AUTH),
            entry("POST", "/api/v1/auth/password/reset/confirm", "비밀번호 재설정", AUTH),
            entry("PATCH", "/api/v1/auth/me", "내 정보 수정", AUTH),
            entry("PATCH", "/api/v1/auth/promote", "관리자 승격", AUTH),
            entry("DELETE", "/api/v1/auth/quit", "회원 탈퇴", AUTH),
            entry("GET", "/api/v1/auth/me", "내 정보 조회", READ),
            entry("POST", "/api/v1/auth/reissue", "토큰 재발급", AUTO),

            // 분석
            entry("POST", "/api/v1/dashboard/analyze/upload", "분석 파일 업로드", ANALYSIS),
            entry("DELETE", "/api/v1/dashboard/analyze/history/{analysisId}", "분석 이력 삭제", ANALYSIS, "analysisId", "분석"),
            entry("GET", "/api/v1/dashboard/usage", "사용량 조회", READ),
            entry("GET", "/api/v1/dashboard/analyze/result", "대시보드 조회", READ),
            entry("GET", "/api/v1/dashboard/analyze/history", "분석 이력 조회", READ),
            entry("GET", "/api/v1/dashboard/analyze/{analysisId}/result", "분석 결과 조회", READ, "analysisId", "분석"),
            entry("GET", "/api/v1/dashboard/analyze/stream/{analysisId}", "분석 실시간 연결", AUTO, "analysisId", "분석"),
            entry("GET", "/api/v1/dashboard/analyze/{analysisId}", "분석 상태 확인", AUTO, "analysisId", "분석"),
            entry("POST", "/api/v1/dashboard/analyze/callback/{analysisId}", "AI 분석 결과 수신", AUTO, "analysisId", "분석"),

            // FAQ
            entry("POST", "/api/v1/faq", "FAQ 등록", FAQ),
            entry("POST", "/api/v1/faq/apply", "FAQ 후보 승인·반영", FAQ),
            entry("POST", "/api/v1/faq/categories", "카테고리 생성", FAQ),
            entry("POST", "/api/v1/faq/import", "FAQ 일괄 업로드", FAQ),
            entry("POST", "/api/v1/faq/versions", "FAQ 버전 만들기", FAQ),
            entry("POST", "/api/v1/faq/versions/{versionId}/restore", "FAQ 버전 복원", FAQ, "versionId", "버전"),
            entry("PATCH", "/api/v1/faq/recommendations/{candidateId}/reject", "FAQ 후보 반려", FAQ, "candidateId", "후보"),
            entry("PATCH", "/api/v1/faq/recommendations/{candidateId}", "FAQ 후보 수정", FAQ, "candidateId", "후보"),
            entry("PATCH", "/api/v1/faq/{faqId}", "FAQ 수정", FAQ, "faqId", "FAQ"),
            entry("DELETE", "/api/v1/faq/{faqId}", "FAQ 삭제", FAQ, "faqId", "FAQ"),
            entry("GET", "/api/v1/faq", "FAQ 목록 조회", READ),
            entry("GET", "/api/v1/faq/categories", "카테고리 조회", READ),
            entry("GET", "/api/v1/faq/history", "FAQ 변경 이력 조회", READ),
            entry("GET", "/api/v1/faq/versions", "FAQ 버전 목록 조회", READ),
            entry("GET", "/api/v1/faq/versions/compare", "FAQ 버전 비교", READ),
            entry("GET", "/api/v1/faq/versions/{versionId}", "FAQ 버전 상세 조회", READ, "versionId", "버전"),
            entry("GET", "/api/v1/faq/recommendations/{analysisId}", "FAQ 후보 조회", READ, "analysisId", "분석"),

            // 데이터 반출 (/faq/download는 아래 /faq/{faqId}보다 먼저 맞춰야 한다)
            entry("GET", "/api/v1/faq/download", "FAQ 다운로드", EXPORT),
            entry("GET", "/api/v1/faq/keywords/download", "키워드 다운로드", EXPORT),
            entry("GET", "/api/v1/audit-logs", "감사로그 조회", EXPORT),

            entry("GET", "/api/v1/faq/{faqId}", "FAQ 상세 조회", READ, "faqId", "FAQ")
    );

    private AuditActionCatalog() {
    }

    // 같은 경로에 맞는 규칙이 여럿이면 표에서 먼저 나온 규칙을 쓴다 (구체적인 경로를 앞에 둔다)
    public static Resolved resolve(String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (Entry e : ENTRIES) {
            if (!e.method().equalsIgnoreCase(method)) continue;
            PathPattern.PathMatchInfo match = e.pattern().matchAndExtract(container);
            if (match == null) continue;
            String target = e.target() != null ? e.targetLabel() + " #" + match.getUriVariables().get(e.target()) : null;
            return new Resolved(e.action(), e.category(), target);
        }
        return new Resolved("기타 요청", "GET".equalsIgnoreCase(method) ? READ : null, null);
    }

    private static Entry entry(String method, String pattern, String action, AuditCategory category) {
        return entry(method, pattern, action, category, null, null);
    }

    private static Entry entry(String method, String pattern, String action, AuditCategory category,
                               String target, String targetLabel) {
        return new Entry(method, PARSER.parse(pattern), action, category, target, targetLabel);
    }
}
