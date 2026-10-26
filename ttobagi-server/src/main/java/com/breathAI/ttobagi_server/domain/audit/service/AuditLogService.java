package com.breathAI.ttobagi_server.domain.audit.service;

import com.breathAI.ttobagi_server.domain.audit.dto.AuditLogListResponse;
import com.breathAI.ttobagi_server.domain.audit.entity.AuditCategory;
import com.breathAI.ttobagi_server.domain.audit.entity.AuditLog;
import com.breathAI.ttobagi_server.domain.audit.repository.AuditLogRepository;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

// API 호출 감사로그 기록과 조회
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    // 기록에 실패해도 원래 요청은 실패시키지 않는다
    public void record(AuditLog auditLog) {
        try {
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("감사로그 기록 실패: {} {}", auditLog.getMethod(), auditLog.getPath(), e);
        }
    }

    // 감사로그 조회, 기본은 주요 작업만(조회는 401·403으로 막힌 것만), includeReads면 조회까지, 자동 요청은 늘 뺀다
    // 기간은 날짜 단위(끝 날짜 포함), result는 success 또는 fail
    @Transactional(readOnly = true)
    public AuditLogListResponse search(boolean includeReads, String category, LocalDate from, LocalDate to,
                                       String email, String method, String path, String result, int page, int size) {
        Boolean success = parseResult(result);
        Page<AuditLog> logPage = auditLogRepository.search(
                AuditCategory.AUTO,
                AuditCategory.READ,
                includeReads,
                parseCategory(category),
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null,
                blankToNull(email),
                method != null && !method.isBlank() ? method.trim().toUpperCase(Locale.ROOT) : null,
                blankToNull(path),
                success,
                PageRequest.of(page, size));

        return AuditLogListResponse.builder()
                .totalCount(logPage.getTotalElements())
                .totalPages(logPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .logs(logPage.getContent().stream()
                        .map(l -> AuditLogListResponse.LogItem.builder()
                                .logId(l.getLogId())
                                .createdAt(l.getCreatedAt())
                                .email(l.getEmail())
                                .method(l.getMethod())
                                .path(l.getPath())
                                .query(l.getQuery())
                                .status(l.getStatus())
                                .success(l.getIsSuccess())
                                .ip(l.getIp())
                                .durationMs(l.getDurationMs())
                                .action(l.getAction())
                                .category(l.getCategory())
                                .target(l.getTarget())
                                .resultLabel(resultLabel(l.getStatus()))
                                .build())
                        .toList())
                .build();
    }

    // 화면에서 고를 수 있는 분류만 받는다 (자동 요청은 보여 주지 않으므로 받지 않는다)
    private static AuditCategory parseCategory(String category) {
        if (category == null || category.isBlank()) return null;
        for (AuditCategory c : AuditCategory.values()) {
            if (c != AuditCategory.AUTO && c.name().equalsIgnoreCase(category.trim())) return c;
        }
        throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
    }

    // 응답 상태를 화면에 보일 결과로 바꾼다
    private static String resultLabel(int status) {
        if (status < 400) return "성공";
        return switch (status) {
            case 400 -> "잘못된 요청";
            case 401 -> "로그인 필요";
            case 403 -> "권한 없음";
            case 404 -> "없음";
            case 409 -> "충돌";
            case 413 -> "용량 초과";
            case 429 -> "횟수 초과";
            default -> status >= 500 ? "서버 오류" : "실패";
        };
    }

    private static Boolean parseResult(String result) {
        if (result == null || result.isBlank()) return null;
        return switch (result.trim().toLowerCase(Locale.ROOT)) {
            case "success" -> true;
            case "fail" -> false;
            default -> throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
