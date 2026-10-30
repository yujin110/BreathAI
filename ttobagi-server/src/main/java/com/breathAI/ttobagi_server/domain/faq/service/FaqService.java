package com.breathAI.ttobagi_server.domain.faq.service;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.faq.dto.*;
import com.breathAI.ttobagi_server.domain.faq.entity.*;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqCandidate.CandidateType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqCandidate.ReviewStatus;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory.EditType;
import com.breathAI.ttobagi_server.domain.faq.repository.*;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

// 운영 FAQ 관리 및 AI 생성 FAQ 후보 반영 처리
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqService {

    private final FaqRepository faqRepository;
    private final FaqCandidateRepository faqCandidateRepository;
    private final FaqCandidateMatchRepository faqCandidateMatchRepository;
    private final SynonymCandidateRepository synonymCandidateRepository;
    private final FaqActionLogRepository faqActionLogRepository;
    private final FaqEditHistoryRepository faqEditHistoryRepository;
    private final FaqVersionRepository faqVersionRepository;
    private final FaqVersionItemRepository faqVersionItemRepository;
    private final FaqCategoryRepository faqCategoryRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // FAQ 목록 조회, 활성 항목만 최신순 페이징
    // versionId가 있으면 그 버전 시점의 FAQ를, 없으면 현재 운영 FAQ를 조회한다
    // 카테고리는 마스터의 표시명과 일치하는 코드로, 키워드는 질문·답변·키워드 목록에서 검색
    @Transactional(readOnly = true)
    public FaqListResponse getFaqList(Long versionId, int page, int size, String category, String keyword) {
        FaqVersion version = null;
        if (versionId != null) {
            version = faqVersionRepository.findById(versionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.FAQ_VERSION_NOT_FOUND));
        }
        Long responseVersionId = version != null ? version.getVersionId() : null;
        String responseVersionName = version != null ? version.getVersionName() : null;

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());

        // 카테고리 조건이 있을 때만 마스터를 조회한다
        String categoryName = blankToNull(category);
        List<Integer> qTypes = List.of();
        if (categoryName != null) {
            qTypes = faqRepository.findQTypesByCategoryName(categoryName);
            // 마스터에 없는 카테고리명이면 결과 없음
            if (qTypes.isEmpty()) {
                return FaqListResponse.builder()
                        .versionId(responseVersionId)
                        .versionName(responseVersionName)
                        .faqList(List.of())
                        .totalCount(0)
                        .totalPages(0)
                        .currentPage(page)
                        .size(size)
                        .build();
            }
        }

        // 저장된 카테고리 이름이 비어 있으면 마스터의 이름을 쓴다, 목록마다 마스터를 한 번만 읽는다
        Map<Integer, String> masterNames = categoryNamesByQType();

        if (version != null) {
            Page<FaqVersionItem> itemPage = faqVersionItemRepository.search(
                    version.getVersionId(), categoryName != null, qTypes, blankToNull(keyword),
                    PageRequest.of(page, size, Sort.by("faqCreatedAt").descending()));

            return FaqListResponse.builder()
                    .versionId(responseVersionId)
                    .versionName(responseVersionName)
                    .faqList(itemPage.getContent().stream()
                            .map(i -> FaqListResponse.FaqItem.builder()
                                    .faqId(i.getFaqId())
                                    .standardQuestion(i.getQuestion())
                                    .answer(i.getAnswer())
                                    .keywords(parseKeywords(i.getKeywords()))
                                    .qType(i.getQType())
                                    .category(displayCategory(i.getCategory(), i.getQType(), masterNames))
                                    .createdAt(i.getFaqCreatedAt().toLocalDate())
                                    .build())
                            .collect(Collectors.toList()))
                    .totalCount(itemPage.getTotalElements())
                    .totalPages(itemPage.getTotalPages())
                    .currentPage(page)
                    .size(size)
                    .build();
        }

        Page<Faq> faqPage = faqRepository.search(
                categoryName != null, qTypes, blankToNull(keyword), pageRequest);

        List<FaqListResponse.FaqItem> items = faqPage.getContent().stream()
                .map(f -> FaqListResponse.FaqItem.builder()
                        .faqId(f.getFaqId())
                        .standardQuestion(f.getQuestion())
                        .answer(f.getAnswer())
                        .keywords(parseKeywords(f.getKeywords()))
                        .qType(f.getQType())
                        .category(displayCategory(f.getCategory(), f.getQType(), masterNames))
                        .createdAt(f.getCreatedAt().toLocalDate())
                        .build())
                .collect(Collectors.toList());

        return FaqListResponse.builder()
                .faqList(items)
                .totalCount(faqPage.getTotalElements())
                .totalPages(faqPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .build();
    }

    // 카테고리 목록 조회, 카테고리 필터 선택지용
    @Transactional(readOnly = true)
    public FaqCategoryListResponse getCategories() {
        return FaqCategoryListResponse.builder()
                .categories(faqRepository.findLatestCategories().stream()
                        .map(c -> FaqCategoryListResponse.CategoryItem.builder()
                                .qType(c.getCode())
                                .name(c.getName())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    // 카테고리 생성, 앞뒤 공백을 지우고 연속 공백은 하나로 줄여 저장한다
    // 공백을 모두 뺀 이름이 기존·새 카테고리와 같으면 409 (예: "열차 운영관련" = "열차운영관련")
    @Transactional
    public FaqCategoryListResponse.CategoryItem createCategory(FaqCategoryCreateRequest request, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 전각 공백 등 유니코드 공백도 공백으로 본다
        String name = request.getName().strip().replaceAll("(?U)\\s+", " ");
        String nameKey = name.replaceAll("(?U)\\s+", "");
        if (nameKey.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (faqCategoryRepository.countByNameKeyInMaster(nameKey) > 0) {
            throw new CustomException(ErrorCode.FAQ_CATEGORY_DUPLICATED);
        }

        FaqCategory category;
        try {
            category = faqCategoryRepository.saveAndFlush(FaqCategory.builder()
                    .qType(faqCategoryRepository.nextCode())
                    .name(name)
                    .nameKey(nameKey)
                    .createdBy(user)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 같은 이름이나 코드로 동시에 만들어진 경우
            throw new CustomException(ErrorCode.FAQ_CATEGORY_DUPLICATED);
        }
        log.info("카테고리 생성: qType={}, name={}", category.getQType(), category.getName());
        return FaqCategoryListResponse.CategoryItem.builder()
                .qType(category.getQType())
                .name(category.getName())
                .build();
    }

    // FAQ 단건 상세 조회
    @Transactional(readOnly = true)
    public FaqDetailResponse getFaqDetail(Long faqId) {
        Faq faq = faqRepository.findByFaqIdAndIsActiveTrue(faqId)
                .orElseThrow(() -> new CustomException(ErrorCode.FAQ_NOT_FOUND));

        return FaqDetailResponse.builder()
                .faqId(faq.getFaqId())
                .standardQuestion(faq.getQuestion())
                .answer(faq.getAnswer())
                .keywords(parseKeywords(faq.getKeywords()))
                .qType(faq.getQType())
                .category(faq.getCategory() != null || faq.getQType() == null
                        ? faq.getCategory()
                        : faqRepository.findCategoryNameByQType(faq.getQType()).orElse(null))
                .qaCnt(faq.getQaCnt())
                .createdAt(faq.getCreatedAt().toLocalDate())
                .updatedAt(faq.getUpdatedAt().toLocalDate())
                .build();
    }

    // FAQ 수정, 변경 전후 값을 이력에 남긴다
    @Transactional
    public FaqListUpdateResponse updateFaq(Long faqId, FaqListUpdateRequest request, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Faq faq = faqRepository.findByFaqIdAndIsActiveTrue(faqId)
                .orElseThrow(() -> new CustomException(ErrorCode.FAQ_NOT_FOUND));

        String beforeQuestion = faq.getQuestion();
        String beforeAnswer = faq.getAnswer();
        List<String> beforeKeywords = parseKeywords(faq.getKeywords());
        Integer beforeQType = faq.getQType();

        faq.update(request.getStandardQuestion(), request.getAnswer(),
                toKeywordsJson(request.getKeywords()), request.getQType());
        syncCategory(faq, beforeQType);
        // 수정 시각이 응답에 반영되도록 즉시 반영한다
        faqRepository.saveAndFlush(faq);

        // 요청값이 아닌 반영 결과를 기록해야 일부 필드만 수정한 경우에도 이력이 정확하다
        List<String> afterKeywords = parseKeywords(faq.getKeywords());
        FaqEditHistory history = FaqEditHistory.builder()
                .faq(faq)
                .editedBy(user)
                .editType(EditType.MANUAL)
                .beforeQuestion(beforeQuestion)
                .beforeAnswer(beforeAnswer)
                .beforeKeywords(beforeKeywords)
                .afterQuestion(faq.getQuestion())
                .afterAnswer(faq.getAnswer())
                .afterKeywords(afterKeywords)
                .editReason(request.getEditReason())
                .build();
        faqEditHistoryRepository.save(history);

        return FaqListUpdateResponse.builder()
                .faqId(faq.getFaqId())
                .qType(faq.getQType())
                .question(faq.getQuestion())
                .answer(faq.getAnswer())
                .keywords(afterKeywords)
                .historyId(history.getHistoryId())
                .updatedAt(faq.getUpdatedAt())
                .build();
    }

    // FAQ 삭제, 실제 삭제 없이 비활성 처리하고 삭제 이력을 남긴다
    @Transactional
    public void deleteFaq(Long faqId, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Faq faq = faqRepository.findByFaqIdAndIsActiveTrue(faqId)
                .orElseThrow(() -> new CustomException(ErrorCode.FAQ_NOT_FOUND));

        faq.deactivate();

        faqEditHistoryRepository.save(FaqEditHistory.builder()
                .faq(faq)
                .editedBy(user)
                .editType(EditType.DELETE)
                .beforeQuestion(faq.getQuestion())
                .beforeAnswer(faq.getAnswer())
                .beforeKeywords(parseKeywords(faq.getKeywords()))
                .build());
        log.info("FAQ 삭제 완료: faqId={}", faqId);
    }

    // FAQ 변경 이력 조회, 최신순 페이징
    // faqId가 있으면 그 FAQ의 이력만 (삭제된 FAQ 포함, 없는 FAQ면 빈 목록)
    @Transactional(readOnly = true)
    public FaqEditHistoryResponse getFaqHistory(int page, int size, Long faqId, EditType editType) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by("createdAt").descending().and(Sort.by("historyId").descending()));
        Page<FaqEditHistory> historyPage = faqEditHistoryRepository.search(faqId, editType, pageRequest);

        List<FaqEditHistoryResponse.HistoryItem> items =
                historyPage.getContent().stream()
                        .map(h -> FaqEditHistoryResponse.HistoryItem.builder()
                                .historyId(h.getHistoryId())
                                .faqId(h.getFaq().getFaqId())
                                .editType(h.getEditType())
                                .analysisId(h.getAnalysisJob() != null
                                        ? h.getAnalysisJob().getAnalysisId() : null)
                                .versionId(h.getVersion() != null
                                        ? h.getVersion().getVersionId() : null)
                                .beforeQuestion(h.getBeforeQuestion())
                                .beforeAnswer(h.getBeforeAnswer())
                                .beforeKeywords(h.getBeforeKeywords())
                                .afterQuestion(h.getAfterQuestion())
                                .afterAnswer(h.getAfterAnswer())
                                .afterKeywords(h.getAfterKeywords())
                                .editReason(h.getEditReason())
                                // 탈퇴한 사용자의 이력은 수정자 정보 없이 반환
                                .editedBy(h.getEditedBy() != null
                                        ? h.getEditedBy().getEmail() : null)
                                .createdAt(h.getCreatedAt())
                                .build())
                        .collect(Collectors.toList());

        return FaqEditHistoryResponse.builder()
                .totalCount(historyPage.getTotalElements())
                .histories(items)
                .totalPages(historyPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .build();
    }

    // 분석 결과 기반 FAQ 후보 추천 목록 조회
    @Transactional(readOnly = true)
    public FaqCandidateListResponse getFaqRecommendations(Long analysisId) {
        List<FaqCandidate> candidates = faqCandidateRepository
                .findByAnalysisJobAnalysisId(analysisId);

        // 후보별 유사 FAQ 매칭과, 매칭된 운영 FAQ를 한 번에 조회한다
        List<FaqCandidateMatch> matches = faqCandidateMatchRepository
                .findByCandidate_AnalysisJob_AnalysisIdOrderByMatchScoreDesc(analysisId);
        Map<Long, List<FaqCandidateMatch>> matchesByCandidate = matches.stream()
                .collect(Collectors.groupingBy(m -> m.getCandidate().getCandidateId()));
        MatchedFaqLookup lookup = matchedFaqLookup(matches);

        List<FaqCandidateListResponse.FaqCandidateItem> items = candidates.stream()
                .map(c -> buildFaqCandidateItem(c,
                        matchesByCandidate.getOrDefault(c.getCandidateId(), List.of()), lookup))
                .collect(Collectors.toList());

        return FaqCandidateListResponse.builder()
                .analysisId(analysisId)
                .recommendations(items)
                .build();
    }

    // FAQ 후보를 운영 FAQ로 반영
    // NEW 후보는 FAQ를 새로 등록하고, EXPAND 후보는 가장 유사한 기존 FAQ를 확장한다
    // 상태 전이는 PENDING -> ACCEPTED -> APPLIED 순이며, 그 외 상태는 반영 대상이 아니다
    @Transactional
    public FaqApplyResponse applyFaqCandidate(FaqApplyRequest request, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        FaqCandidate candidate = faqCandidateRepository.findById(request.getCandidateId())
                .orElseThrow(() -> new CustomException(ErrorCode.FAQ_CANDIDATE_NOT_FOUND));

        ReviewStatus beforeStatus = candidate.getReviewStatus();

        // 이미 반영되었거나 반려된 후보는 재반영할 수 없다
        if (beforeStatus != ReviewStatus.PENDING && beforeStatus != ReviewStatus.ACCEPTED) {
            throw new CustomException(ErrorCode.FAQ_CANDIDATE_NOT_APPLICABLE);
        }

        boolean isExpand = candidate.getCandidateType() == CandidateType.EXPAND;

        // 확장 대상이 없으면 후보 상태를 바꾸기 전에 거부한다
        Faq targetFaq = isExpand ? findExpandTarget(candidate) : null;

        // 승인 절차를 거치지 않은 후보는 반영과 동시에 승인 처리한다
        if (beforeStatus == ReviewStatus.PENDING) {
            candidate.accept(user);
        }
        candidate.apply();

        Faq appliedFaq;
        String beforeQuestion = null;
        String beforeAnswer = null;
        List<String> beforeKeywords = null;

        if (isExpand) {
            beforeQuestion = targetFaq.getQuestion();
            beforeAnswer = targetFaq.getAnswer();
            beforeKeywords = parseKeywords(targetFaq.getKeywords());

            // 키워드는 기존 것에 합치고, 질문과 답변은 운영자가 확정한 값으로 바꾼다
            LinkedHashSet<String> mergedKeywords = new LinkedHashSet<>(beforeKeywords);
            if (request.getKeywords() != null) {
                mergedKeywords.addAll(request.getKeywords());
            }
            Integer beforeQType = targetFaq.getQType();
            targetFaq.update(request.getFinalQuestion(), request.getFinalAnswer(),
                    toKeywordsJson(new ArrayList<>(mergedKeywords)), request.getQType());
            syncCategory(targetFaq, beforeQType);
            // 수정 시각이 응답에 반영되도록 즉시 반영한다
            appliedFaq = faqRepository.saveAndFlush(targetFaq);
        } else {
            // 운영자가 카테고리 코드를 지정하지 않으면 후보의 값을 따르고, 바꾸면 카테고리 이름도 마스터에서 다시 찾는다
            Integer qType = request.getQType() != null ? request.getQType() : candidate.getQType();
            String category = Objects.equals(qType, candidate.getQType())
                    ? candidate.getCategory()
                    : faqRepository.findCategoryNameByQType(qType).orElse(null);
            appliedFaq = faqRepository.save(Faq.builder()
                    .candidate(candidate)
                    .question(request.getFinalQuestion())
                    .answer(request.getFinalAnswer())
                    .keywords(toKeywordsJson(request.getKeywords()))
                    .qType(qType)
                    .category(category)
                    .createdBy(user)
                    .build());
        }

        FaqActionLog actionLog = FaqActionLog.builder()
                .candidate(candidate)
                .actedBy(user)
                .action(FaqActionLog.Action.APPLY)
                .beforeStatus(beforeStatus)
                .afterStatus(ReviewStatus.APPLIED)
                .note(Boolean.TRUE.equals(request.getIsManualPatchConfirmed())
                        ? "수동 반영 확인됨" : null)
                .build();
        faqActionLogRepository.save(actionLog);

        // 신규 등록은 CREATE, 기존 FAQ 확장은 EXPAND로 이력을 남긴다
        EditType editType = isExpand ? EditType.EXPAND : EditType.CREATE;
        FaqEditHistory history = FaqEditHistory.builder()
                .faq(appliedFaq)
                .analysisJob(candidate.getAnalysisJob())
                .editedBy(user)
                .editType(editType)
                .beforeQuestion(beforeQuestion)
                .beforeAnswer(beforeAnswer)
                .beforeKeywords(beforeKeywords)
                .afterQuestion(appliedFaq.getQuestion())
                .afterAnswer(appliedFaq.getAnswer())
                .afterKeywords(parseKeywords(appliedFaq.getKeywords()))
                .build();
        faqEditHistoryRepository.save(history);

        log.info("FAQ 후보 반영 완료: candidateId={}, faqId={}, editType={}",
                candidate.getCandidateId(), appliedFaq.getFaqId(), editType);

        return FaqApplyResponse.builder()
                .appliedFaqId(appliedFaq.getFaqId())
                .candidateId(candidate.getCandidateId())
                .editType(editType)
                .historyId(history.getHistoryId())
                .appliedAt(isExpand ? appliedFaq.getUpdatedAt() : appliedFaq.getCreatedAt())
                .build();
    }

    // FAQ 후보 반려, 대기 중인 후보만 가능하고 사유를 검토 기록에 남긴다
    @Transactional
    public void rejectCandidate(Long candidateId, String reason, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        FaqCandidate candidate = findPendingCandidate(candidateId);

        candidate.reject(user);
        faqActionLogRepository.save(FaqActionLog.builder()
                .candidate(candidate)
                .actedBy(user)
                .action(FaqActionLog.Action.REJECT)
                .beforeStatus(ReviewStatus.PENDING)
                .afterStatus(ReviewStatus.REJECTED)
                .note(blankToNull(reason))
                .build());
        log.info("FAQ 후보 반려: candidateId={}", candidateId);
    }

    // FAQ 후보 초안 수정, 대기 중인 후보만 가능하고 바뀐 칸의 전후 값을 검토 기록에 남긴다
    // 카테고리 코드가 바뀌면 카테고리 이름을 마스터 기준으로 맞춘다
    @Transactional
    public FaqCandidateListResponse.FaqCandidateItem updateCandidate(Long candidateId,
                                                                     FaqCandidateUpdateRequest request, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        FaqCandidate candidate = findPendingCandidate(candidateId);

        Map<String, Object> before = candidateFields(candidate);
        // 카테고리 코드를 생략하면 기존 값을 유지한다 (FAQ 수정과 같은 규칙)
        Integer qType = request.getQType() != null ? request.getQType() : candidate.getQType();
        String category = Objects.equals(qType, candidate.getQType())
                ? candidate.getCategory()
                : faqRepository.findCategoryNameByQType(qType).orElse(null);
        candidate.updateDraft(request.getStandardQuestion(), request.getAnswerDraft(),
                request.getKeywords(), qType, category);
        faqCandidateRepository.saveAndFlush(candidate);
        Map<String, Object> after = candidateFields(candidate);

        // 바뀐 칸만 기록한다
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        before.forEach((field, value) -> {
            if (!Objects.equals(value, after.get(field))) {
                changedBefore.put(field, value);
                changedAfter.put(field, after.get(field));
            }
        });
        if (!changedBefore.isEmpty()) {
            faqActionLogRepository.save(FaqActionLog.builder()
                    .candidate(candidate)
                    .actedBy(user)
                    .action(FaqActionLog.Action.EDIT)
                    .beforeStatus(ReviewStatus.PENDING)
                    .afterStatus(ReviewStatus.PENDING)
                    .note(toJson(Map.of("before", changedBefore, "after", changedAfter)))
                    .build());
        }

        List<FaqCandidateMatch> matches = faqCandidateMatchRepository
                .findByCandidateCandidateIdOrderByMatchScoreDesc(candidateId);
        return buildFaqCandidateItem(candidate, matches, matchedFaqLookup(matches));
    }

    // FAQ 직접 등록, 변경 이력에 등록(CREATE)으로 남긴다
    @Transactional
    public FaqDetailResponse createFaq(FaqCreateRequest request, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Faq faq = faqRepository.saveAndFlush(Faq.builder()
                .question(request.getStandardQuestion())
                .answer(request.getAnswer())
                .keywords(toKeywordsJson(request.getKeywords()))
                .qType(request.getQType())
                .category(request.getQType() != null
                        ? faqRepository.findCategoryNameByQType(request.getQType()).orElse(null) : null)
                .createdBy(user)
                .build());

        faqEditHistoryRepository.save(FaqEditHistory.builder()
                .faq(faq)
                .editedBy(user)
                .editType(EditType.CREATE)
                .afterQuestion(faq.getQuestion())
                .afterAnswer(faq.getAnswer())
                .afterKeywords(parseKeywords(faq.getKeywords()))
                .build());
        log.info("FAQ 직접 등록: faqId={}", faq.getFaqId());
        return getFaqDetail(faq.getFaqId());
    }

    // 검토할 수 있는(대기 중인) 후보 조회, 없으면 404, 대기 상태가 아니면 409
    private FaqCandidate findPendingCandidate(Long candidateId) {
        FaqCandidate candidate = faqCandidateRepository.findById(candidateId)
                .orElseThrow(() -> new CustomException(ErrorCode.FAQ_CANDIDATE_NOT_FOUND));
        if (candidate.getReviewStatus() != ReviewStatus.PENDING) {
            throw new CustomException(ErrorCode.FAQ_CANDIDATE_NOT_REVIEWABLE);
        }
        return candidate;
    }

    // 후보 수정 기록용 칸 값, 이름은 수정 요청의 필드 이름과 맞춘다
    private Map<String, Object> candidateFields(FaqCandidate candidate) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("standardQuestion", candidate.getStandardQuestion());
        fields.put("answerDraft", candidate.getAnswerDraft());
        fields.put("keywords", candidate.getRepresentativeKeywords() != null
                ? new ArrayList<>(candidate.getRepresentativeKeywords()) : null);
        fields.put("qType", candidate.getQType());
        fields.put("category", candidate.getCategory());
        return fields;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("검토 기록 직렬화 실패");
            return null;
        }
    }

    // EXPAND 후보의 확장 대상 조회
    // 후보의 유사 FAQ 중 점수가 가장 높은 것을 운영 FAQ ID로 찾는다
    // ID가 있으면 ID만 믿고(없거나 삭제됐으면 409), ID가 없는 예전 분석만 원본 순번으로 찾는다
    private Faq findExpandTarget(FaqCandidate candidate) {
        return faqCandidateMatchRepository
                .findByCandidateCandidateIdOrderByMatchScoreDesc(candidate.getCandidateId()).stream()
                .findFirst()
                .flatMap(m -> {
                    if (m.getMatchedFaqId() != null) {
                        return faqRepository.findByFaqIdAndIsActiveTrue(m.getMatchedFaqId());
                    }
                    if (m.getMatchedFaqSeqNum() != null) {
                        return faqRepository
                                .findFirstBySourceSeqNumAndIsActiveTrueOrderByFaqIdAsc(m.getMatchedFaqSeqNum());
                    }
                    return Optional.empty();
                })
                .orElseThrow(() -> new CustomException(ErrorCode.FAQ_EXPAND_TARGET_NOT_FOUND));
    }

    // 매칭된 운영 FAQ를 ID와 원본 순번으로 한 번에 조회해 둔다
    private MatchedFaqLookup matchedFaqLookup(List<FaqCandidateMatch> matches) {
        List<Long> faqIds = matches.stream()
                .map(FaqCandidateMatch::getMatchedFaqId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Integer> seqNums = matches.stream()
                .filter(m -> m.getMatchedFaqId() == null)
                .map(FaqCandidateMatch::getMatchedFaqSeqNum)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Faq> byId = faqIds.isEmpty()
                ? Map.of()
                : faqRepository.findAllById(faqIds).stream()
                        .collect(Collectors.toMap(Faq::getFaqId, f -> f));
        Map<Integer, Faq> bySeqNum = seqNums.isEmpty()
                ? Map.of()
                : faqRepository.findBySourceSeqNumIn(seqNums).stream()
                        .collect(Collectors.toMap(Faq::getSourceSeqNum, f -> f, (a, b) -> a));
        return new MatchedFaqLookup(byId, bySeqNum);
    }

    // 매칭 한 건의 운영 FAQ, 확장 대상 조회와 같은 기준(ID 우선, ID가 없을 때만 원본 순번)
    private record MatchedFaqLookup(Map<Long, Faq> byId, Map<Integer, Faq> bySeqNum) {
        Faq find(FaqCandidateMatch match) {
            if (match.getMatchedFaqId() != null) {
                return byId.get(match.getMatchedFaqId());
            }
            return match.getMatchedFaqSeqNum() != null ? bySeqNum.get(match.getMatchedFaqSeqNum()) : null;
        }
    }

    // 후보 단건을 응답 DTO로 변환, 유사어와 유사 FAQ 목록 포함
    private FaqCandidateListResponse.FaqCandidateItem buildFaqCandidateItem(
            FaqCandidate candidate, List<FaqCandidateMatch> matches, MatchedFaqLookup lookup) {
        List<FaqCandidateListResponse.FaqCandidateItem.SynonymItem> synonyms =
                synonymCandidateRepository
                        .findByCandidateCandidateId(candidate.getCandidateId())
                        .stream()
                        .map(s -> FaqCandidateListResponse.FaqCandidateItem.SynonymItem.builder()
                                .text(s.getSynonymText())
                                .type(s.getSynonymType())
                                .build())
                        .collect(Collectors.toList());

        // 매칭된 FAQ가 운영 FAQ에 없으면 ID와 질문은 비워 둔다
        List<FaqCandidateListResponse.FaqCandidateItem.MatchedFaqItem> matchedFaqs = matches.stream()
                .map(m -> {
                    Faq matched = lookup.find(m);
                    return FaqCandidateListResponse.FaqCandidateItem.MatchedFaqItem.builder()
                            .matchedFaqId(matched != null ? matched.getFaqId() : null)
                            .matchedFaqSeqNum(m.getMatchedFaqSeqNum())
                            .question(matched != null ? matched.getQuestion() : null)
                            .matchScore(m.getMatchScore().doubleValue())
                            .build();
                })
                .collect(Collectors.toList());

        return FaqCandidateListResponse.FaqCandidateItem.builder()
                .clusterId(candidate.getCluster() != null
                        ? candidate.getCluster().getClusterId() : null)
                .clusterLabel(candidate.getCluster() != null
                        ? candidate.getCluster().getClusterLabel() : null)
                .candidateId(candidate.getCandidateId())
                .candidateType(candidate.getCandidateType())
                .qType(candidate.getQType())
                .category(candidate.getCategory())
                .standardQuestion(candidate.getStandardQuestion())
                .similarQuestions(candidate.getSimilarQuestions())
                .answerDraft(candidate.getAnswerDraft())
                .reviewStatus(candidate.getReviewStatus())
                .representativeKeywords(candidate.getRepresentativeKeywords())
                .occurrenceCount(candidate.getOccurrenceCount())
                .synonyms(synonyms)
                .matchedFaqs(matchedFaqs)
                .createdAt(candidate.getCreatedAt())
                .build();
    }

    // 카테고리 코드가 바뀌었을 때만 표시명을 마스터 기준으로 맞춘다 (마스터에 없는 코드면 비운다)
    // 같은 코드로 다시 저장할 때 마스터에 없는 옛 코드의 카테고리가 지워지지 않게 한다
    private void syncCategory(Faq faq, Integer beforeQType) {
        if (Objects.equals(beforeQType, faq.getQType())) {
            return;
        }
        faq.changeCategory(faqRepository.findCategoryNameByQType(faq.getQType()).orElse(null));
    }

    // 키워드 리스트를 저장용 JSON 문자열로 변환, 없거나 실패하면 null
    private String toKeywordsJson(List<String> keywords) {
        if (keywords == null) return null;
        try {
            return objectMapper.writeValueAsString(keywords);
        } catch (Exception e) {
            log.warn("키워드 직렬화 실패");
            return null;
        }
    }

    // 빈 검색 조건은 미지정으로 취급
    // 화면에 보일 카테고리 이름, 저장된 이름이 있으면 그대로 두고 없을 때만 마스터에서 찾는다
    // 저장된 이름은 카테고리 코드가 바뀔 때만 마스터로 갱신한다는 규칙을 따른다
    private String displayCategory(String stored, Integer qType, Map<Integer, String> masterNames) {
        if (stored != null || qType == null) return stored;
        return masterNames.get(qType);
    }

    private Map<Integer, String> categoryNamesByQType() {
        return faqRepository.findLatestCategories().stream()
                .collect(Collectors.toMap(FaqRepository.CategoryRow::getCode, FaqRepository.CategoryRow::getName));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    // JSON 문자열로 저장된 키워드를 리스트로 변환, 실패 시 빈 리스트 반환
    private List<String> parseKeywords(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("키워드 파싱 실패: {}", json);
            return List.of();
        }
    }
}
