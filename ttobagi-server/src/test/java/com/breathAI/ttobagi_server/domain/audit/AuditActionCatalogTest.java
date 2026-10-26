package com.breathAI.ttobagi_server.domain.audit;

import com.breathAI.ttobagi_server.domain.audit.entity.AuditCategory;
import com.breathAI.ttobagi_server.domain.audit.service.AuditActionCatalog;
import com.breathAI.ttobagi_server.domain.audit.service.AuditActionCatalog.Resolved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 감사로그 작업 분류표, 경로가 겹치는 규칙은 구체적인 쪽으로 정해져야 한다
class AuditActionCatalogTest {

    @Test
    @DisplayName("작업 이름·분류·대상을 메서드와 경로로 정한다")
    void resolvesActions() {
        assertThat(AuditActionCatalog.resolve("POST", "/api/v1/auth/login"))
                .isEqualTo(new Resolved("로그인", AuditCategory.AUTH, null));
        assertThat(AuditActionCatalog.resolve("PATCH", "/api/v1/faq/123"))
                .isEqualTo(new Resolved("FAQ 수정", AuditCategory.FAQ, "FAQ #123"));
        assertThat(AuditActionCatalog.resolve("POST", "/api/v1/faq/versions/3/restore"))
                .isEqualTo(new Resolved("FAQ 버전 복원", AuditCategory.FAQ, "버전 #3"));
        assertThat(AuditActionCatalog.resolve("PATCH", "/api/v1/faq/recommendations/7/reject"))
                .isEqualTo(new Resolved("FAQ 후보 반려", AuditCategory.FAQ, "후보 #7"));
        assertThat(AuditActionCatalog.resolve("DELETE", "/api/v1/dashboard/analyze/history/8"))
                .isEqualTo(new Resolved("분석 이력 삭제", AuditCategory.ANALYSIS, "분석 #8"));
    }

    @Test
    @DisplayName("겹치는 경로는 구체적인 규칙이 먼저 맞는다")
    void specificPathsWin() {
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/faq/download").category()).isEqualTo(AuditCategory.EXPORT);
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/faq/categories").action()).isEqualTo("카테고리 조회");
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/faq/versions/compare").action()).isEqualTo("FAQ 버전 비교");
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/dashboard/analyze/history").category()).isEqualTo(AuditCategory.READ);
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/dashboard/analyze/result").category()).isEqualTo(AuditCategory.READ);
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/dashboard/analyze/8").category()).isEqualTo(AuditCategory.AUTO);
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/dashboard/analyze/8/result").category()).isEqualTo(AuditCategory.READ);
        assertThat(AuditActionCatalog.resolve("POST", "/api/v1/dashboard/analyze/callback/8").category()).isEqualTo(AuditCategory.AUTO);
    }

    @Test
    @DisplayName("표에 없는 요청은 기타 요청이고, GET만 조회로 분류한다")
    void unknownRequests() {
        assertThat(AuditActionCatalog.resolve("GET", "/api/v1/nothing")).isEqualTo(new Resolved("기타 요청", AuditCategory.READ, null));
        assertThat(AuditActionCatalog.resolve("PUT", "/api/v1/faq/1")).isEqualTo(new Resolved("기타 요청", null, null));
    }
}
