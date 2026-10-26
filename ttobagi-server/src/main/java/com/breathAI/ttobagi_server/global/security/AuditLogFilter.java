package com.breathAI.ttobagi_server.global.security;

import com.breathAI.ttobagi_server.domain.audit.entity.AuditLog;
import com.breathAI.ttobagi_server.domain.audit.service.AuditActionCatalog;
import com.breathAI.ttobagi_server.domain.audit.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 모든 API 호출을 감사로그로 남긴다 (요청 본문은 저장하지 않는다)
// 보안 필터 체인 안 JWT 필터 바로 뒤에 두어, 토큰의 사용자를 알 수 있고 401·403으로 막힌 요청도 남긴다
@Component
@RequiredArgsConstructor
public class AuditLogFilter extends OncePerRequestFilter {

    // 로그인 전 요청(가입·로그인·비밀번호 찾기)은 컨트롤러가 요청에 적힌 이메일을 여기에 넣는다
    private static final String EMAIL_ATTRIBUTE = AuditLogFilter.class.getName() + ".email";

    private final AuditLogService auditLogService;

    public static void setRequestEmail(HttpServletRequest request, String email) {
        request.setAttribute(EMAIL_ATTRIBUTE, email);
    }

    // API가 아닌 요청(서버 상태 확인, Swagger)과 브라우저 사전 확인(OPTIONS)은 남기지 않는다
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            filterChain.doFilter(request, response);
            failed = false;
        } finally {
            // 처리되지 않은 예외로 끝나면 응답 상태가 아직 정해지지 않았을 수 있어 500으로 남긴다
            int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            AuditActionCatalog.Resolved resolved = AuditActionCatalog.resolve(request.getMethod(), request.getRequestURI());
            auditLogService.record(AuditLog.builder()
                    .email(resolveEmail(request))
                    .method(request.getMethod())
                    .path(truncate(request.getRequestURI(), 500))
                    .query(truncate(request.getQueryString(), 1000))
                    .status(status)
                    .ip(resolveIp(request))
                    .durationMs((System.nanoTime() - start) / 1_000_000)
                    .action(resolved.action())
                    .category(resolved.category())
                    .target(truncate(resolved.target(), 100))
                    .build());
        }
    }

    private static String resolveEmail(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && !(auth instanceof AnonymousAuthenticationToken) && auth.getPrincipal() instanceof String email) {
            return email;
        }
        Object requested = request.getAttribute(EMAIL_ATTRIBUTE);
        return requested instanceof String email ? truncate(email.toLowerCase(), 255) : null;
    }

    // nginx가 넘기는 실제 접속 IP, 없으면(AI 콜백 등 nginx를 거치지 않은 요청) 접속 주소
    private static String resolveIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return truncate(realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr(), 64);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
