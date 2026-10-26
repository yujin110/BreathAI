package com.breathAI.ttobagi_server.domain.audit.controller;

import com.breathAI.ttobagi_server.domain.audit.dto.AuditLogListResponse;
import com.breathAI.ttobagi_server.domain.audit.service.AuditLogService;
import com.breathAI.ttobagi_server.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// 감사로그 조회 API, 관리자 전용
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    // 감사로그 목록 조회, 최신순 페이징
    // 기본은 주요 작업만, includeReads=true면 일반 조회까지 (자동 요청은 늘 빠진다)
    // category는 AUTH·ANALYSIS·FAQ·EXPORT·READ, from·to는 yyyy-MM-dd(끝 날짜 포함), email·path는 일부만 맞아도 찾고, result는 success 또는 fail
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AuditLogListResponse>> getAuditLogs(
            @RequestParam(defaultValue = "false") boolean includeReads,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                auditLogService.search(includeReads, category, from, to, email, method, path, result, page, size),
                "감사로그를 조회했습니다."));
    }
}
