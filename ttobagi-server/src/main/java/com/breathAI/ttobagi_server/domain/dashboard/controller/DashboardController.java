package com.breathAI.ttobagi_server.domain.dashboard.controller;

import com.breathAI.ttobagi_server.domain.dashboard.dto.*;
import com.breathAI.ttobagi_server.domain.dashboard.service.DashboardService;
import com.breathAI.ttobagi_server.global.dto.ApiResponse;
import com.breathAI.ttobagi_server.global.security.AiCallbackTokenVerifier;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.util.List;
import java.time.LocalDate;

// 대시보드 조회, 파일 업로드 및 분석 실행 API
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final AiCallbackTokenVerifier aiCallbackTokenVerifier;

    // 월 단위 챗봇 사용량 조회
    @GetMapping("/usage")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<UsageResponse>> getUsage(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getUsage(year, month),
                "챗봇 사용량 조회가 완료되었습니다."));
    }

    // 기간 기반 분석 결과 조회, 기간 미지정 시 최신 분석 반환
    // scope=mine(기본)은 내 이력의 분석만, all은 완료된 분석 전체
    @GetMapping("/analyze/result")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AnalyzeResultResponse>> getAnalyzeResult(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "mine") String scope,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getAnalyzeResult(startDate, endDate, HistoryScope.from(scope), email),
                "대시보드 분석 데이터 조회가 완료되었습니다."));
    }

    // 분석 ID 단위 결과 조회
    @GetMapping("/analyze/{analysisId}/result")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FileAnalyzeResultResponse>> getFileAnalyzeResult(
            @PathVariable Long analysisId) {
        
        return ResponseEntity.ok(ApiResponse.success(
            dashboardService.getFileAnalyzeResult(analysisId),
            "대시보드 분석 데이터 조회가 완료되었습니다."));
    }

    // 파일 업로드 및 분석 시작, 관리자 전용
    @PostMapping("/analyze/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<NewAnalyzeResponse>> uploadAndStartAnalysis(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "periodStartDate", required = false) String periodStartDate,
            @RequestParam(value = "periodEndDate", required = false) String periodEndDate,
            @RequestParam(value = "isMaskingEnabled", defaultValue = "true") boolean isMaskingEnabled,
            @RequestParam(value = "isTranslationEnabled", defaultValue = "false") boolean isTranslationEnabled,
            @RequestParam(value = "minClusterSize", required = false) Integer minClusterSize,
            @RequestParam(value = "minSamples", required = false) Integer minSamples,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.uploadAndStartAnalysis(file, email, periodStartDate, periodEndDate,
                        isMaskingEnabled, isTranslationEnabled, minClusterSize, minSamples),
                "분석 파이프라인이 성공적으로 시작되었습니다."));
    }

    // 분석 진행 상태 조회, SSE 끊김 시 폴링 용도
    @GetMapping("/analyze/{analysisId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AnalyzeStatusResponse>> getAnalysisStatus(
            @PathVariable Long analysisId) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getAnalysisStatus(analysisId),
                "현재 분석 진행 상태를 조회합니다."));
    }

    // 분석 이력 목록 조회, scope=mine(기본)은 내 이력만, all은 모든 사람의 이력
    // status를 주면 그 상태의 분석만 보여 준다 (예: COMPLETED)
    @GetMapping("/analyze/history")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AnalyzeHistoryResponse>> getAnalysisHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getAnalysisHistory(page, size, HistoryScope.from(scope), status, email),
                "분석 이력 목록 조회가 완료되었습니다."));
    }

    // 내 분석 이력 삭제, 분석 결과와 반영된 FAQ는 그대로 남는다
    @DeleteMapping("/analyze/history/{analysisId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteAnalysisHistory(
            @PathVariable Long analysisId,
            @AuthenticationPrincipal String email) {
        dashboardService.deleteAnalysisHistory(analysisId, email);
        return ResponseEntity.ok(ApiResponse.success(null, "분석 이력이 삭제되었습니다."));
    }

    // AI 서버 분석 콜백 수신
    // 사용자 로그인이 아닌 AI 서버와 공유한 콜백 토큰으로 인증한다
    @PostMapping("/analyze/callback/{analysisId}")
    public ResponseEntity<ApiResponse<Void>> receiveAnalysisCallback(
            @PathVariable Long analysisId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AnalysisCallbackRequest request) {
        aiCallbackTokenVerifier.verify(authorization);
        dashboardService.handleAnalysisCallback(analysisId, request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 분석 진행 상태 실시간 스트리밍(SSE), 연결 즉시 현재 상태를 한 번 전송
    @GetMapping(value = "/analyze/stream/{analysisId}", produces = "text/event-stream")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public SseEmitter streamAnalysisStatus(@PathVariable Long analysisId) {
        return dashboardService.subscribeAnalysisStatus(analysisId);
    }
}