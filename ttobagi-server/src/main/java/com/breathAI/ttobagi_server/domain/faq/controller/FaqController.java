package com.breathAI.ttobagi_server.domain.faq.controller;

import com.breathAI.ttobagi_server.domain.faq.dto.*;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory.EditType;
import com.breathAI.ttobagi_server.domain.faq.service.FaqExportService;
import com.breathAI.ttobagi_server.domain.faq.service.FaqService;
import com.breathAI.ttobagi_server.domain.faq.service.FaqVersionService;
import com.breathAI.ttobagi_server.global.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

// FAQ 조회 및 관리 API
// 조회는 전체 사용자, 생성·수정·삭제는 관리자로 제한
@RestController
@RequestMapping("/api/v1/faq")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService;
    private final FaqVersionService faqVersionService;
    private final FaqExportService faqExportService;

    // FAQ 목록 조회, 카테고리와 키워드로 검색 가능
    // versionId를 주면 그 버전 시점의 FAQ를, 생략하면 현재 운영 FAQ를 조회한다
    @GetMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqListResponse>> getFaqList(
            @RequestParam(required = false) Long versionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.success(faqService.getFaqList(versionId, page, size, category, keyword)));
    }

    // 카테고리 목록 조회, 카테고리 필터 선택지용
    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqCategoryListResponse>> getCategories() {
        return ResponseEntity.ok(ApiResponse.success(faqService.getCategories(), "카테고리 목록을 조회했습니다."));
    }

    // 카테고리 생성, 같은 이름(공백 무시)이 있으면 409
    @PostMapping("/categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FaqCategoryListResponse.CategoryItem>> createCategory(
            @RequestBody @Valid FaqCategoryCreateRequest request,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(faqService.createCategory(request, email), "카테고리가 등록되었습니다."));
    }

    // FAQ 단건 상세 조회
    @GetMapping("/{faqId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqDetailResponse>> getFaqDetail(
            @PathVariable Long faqId) {
        return ResponseEntity.ok(ApiResponse.success(faqService.getFaqDetail(faqId), "FAQ를 성공적으로 조회했습니다."));
    }

    // FAQ 직접 등록
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FaqDetailResponse>> createFaq(
            @RequestBody @Valid FaqCreateRequest request,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(faqService.createFaq(request, email), "FAQ가 등록되었습니다."));
    }

    // FAQ 수정, 전달된 필드만 반영
    @PatchMapping("/{faqId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FaqListUpdateResponse>> updateFaq(
            @PathVariable Long faqId,
            @RequestBody @Valid FaqListUpdateRequest request,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(faqService.updateFaq(faqId, request, email), "FAQ가 성공적으로 수정되었습니다."));
    }

    // FAQ 삭제, 비활성 처리
    @DeleteMapping("/{faqId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteFaq(
            @PathVariable Long faqId,
            @AuthenticationPrincipal String email) {
        faqService.deleteFaq(faqId, email);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // FAQ 버전 목록 조회
    @GetMapping("/versions")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqVersionListResponse>> getFaqVersions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(ApiResponse.success(faqVersionService.getVersions(page, size)));
    }

    // FAQ 버전 직접 만들기, 다운로드 없이 현재 상태를 저장
    @PostMapping("/versions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FaqVersionDetailResponse>> createFaqVersion(
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(faqVersionService.createManualVersion(email), "FAQ 버전을 만들었습니다."));
    }

    // FAQ 버전 비교, targetId를 생략하면 현재 운영 FAQ와 비교
    @GetMapping("/versions/compare")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqVersionCompareResponse>> compareFaqVersions(
            @RequestParam Long baseId,
            @RequestParam(required = false) Long targetId) {
        return ResponseEntity.ok(ApiResponse.success(faqVersionService.compareVersions(baseId, targetId), "FAQ 버전을 비교했습니다."));
    }

    // FAQ 버전 복원, 현재 FAQ를 그 버전 내용으로 되돌리고 결과를 새 버전으로 남긴다
    @PostMapping("/versions/{versionId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FaqVersionRestoreResponse>> restoreFaqVersion(
            @PathVariable Long versionId,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(faqVersionService.restoreVersion(versionId, email), "FAQ 버전을 복원했습니다."));
    }

    // FAQ 버전 상세 조회, 버전에 묶인 변경 목록 포함
    @GetMapping("/versions/{versionId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqVersionDetailResponse>> getFaqVersionDetail(
            @PathVariable Long versionId) {
        return ResponseEntity.ok(ApiResponse.success(faqVersionService.getVersionDetail(versionId)));
    }

    // FAQ를 CSV 파일로 다운로드
    // versionId를 주면 그 버전을, 생략하면 최신 내용을 내려준다 (변경이 있으면 새 버전 생성)
    @GetMapping("/download")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<byte[]> downloadFaq(
            @RequestParam(required = false) Long versionId,
            @AuthenticationPrincipal String email) {
        FaqExportService.ExportFile file = faqExportService.export(versionId, email);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }

    // FAQ 변경 이력 조회, FAQ와 변경 유형으로 필터링 가능
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqEditHistoryResponse>> getFaqHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long faqId,
            @RequestParam(required = false) EditType editType) {
        return ResponseEntity.ok(ApiResponse.success(faqService.getFaqHistory(page, size, faqId, editType), "FAQ 수정 이력을 성공적으로 조회했습니다."));
    }

    // 분석 결과 기반 FAQ 후보 추천 목록 조회
    @GetMapping("/recommendations/{analysisId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<ApiResponse<FaqCandidateListResponse>> getFaqRecommendations(
            @PathVariable Long analysisId) {
        return ResponseEntity.ok(ApiResponse.success(faqService.getFaqRecommendations(analysisId), "추천 FAQ 리스트를 성공적으로 조회했습니다."));
    }

    // FAQ 후보 초안 수정, 승인 전에 내용만 저장
    @PatchMapping("/recommendations/{candidateId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FaqCandidateListResponse.FaqCandidateItem>> updateFaqCandidate(
            @PathVariable Long candidateId,
            @RequestBody @Valid FaqCandidateUpdateRequest request,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(faqService.updateCandidate(candidateId, request, email), "FAQ 후보를 수정했습니다."));
    }

    // FAQ 후보 반려, 사유는 선택
    @PatchMapping("/recommendations/{candidateId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> rejectFaqCandidate(
            @PathVariable Long candidateId,
            @RequestBody(required = false) FaqCandidateRejectRequest request,
            @AuthenticationPrincipal String email) {
        faqService.rejectCandidate(candidateId, request != null ? request.getReason() : null, email);
        return ResponseEntity.ok(ApiResponse.success(null, "FAQ 후보를 반려했습니다."));
    }

    // FAQ 후보를 운영 FAQ로 반영
    @PostMapping("/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FaqApplyResponse>> applyFaqCandidate(
            @RequestBody @Valid FaqApplyRequest request,
            @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(ApiResponse.success(faqService.applyFaqCandidate(request, email), "신규 FAQ가 성공적으로 반영되었습니다."));
    }
}
