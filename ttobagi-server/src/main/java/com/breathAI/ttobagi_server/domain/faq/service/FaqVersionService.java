package com.breathAI.ttobagi_server.domain.faq.service;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqVersionCompareResponse;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqVersionDetailResponse;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqVersionListResponse;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqVersionRestoreResponse;
import com.breathAI.ttobagi_server.domain.faq.entity.Faq;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqActionLog;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqCandidate;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqCandidate.ReviewStatus;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory.EditType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion.VersionType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersionItem;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqActionLogRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqEditHistoryRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionItemRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionRepository;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// FAQ 버전 생성 및 조회
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqVersionService {

    // 버전 이름의 날짜는 운영자가 보는 값이라 한국 시간 기준으로 붙인다
    private static final ZoneId NAME_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final FaqVersionRepository faqVersionRepository;
    private final FaqVersionItemRepository faqVersionItemRepository;
    private final FaqEditHistoryRepository faqEditHistoryRepository;
    private final FaqRepository faqRepository;
    private final FaqActionLogRepository faqActionLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // 버전 생성, 엑셀 업로드·다운로드 시점에 호출된다
    // 직전 버전 이후의 변경 이력을 새 버전에 묶고, 현재 활성 FAQ 전체를 사본으로 남긴다
    @Transactional
    public FaqVersion createVersion(VersionType versionType, User createdBy) {
        FaqVersion previous = faqVersionRepository.findTopByOrderByVersionIdDesc().orElse(null);

        Map<EditType, Long> pendingCounts = new EnumMap<>(EditType.class);
        for (Object[] row : faqEditHistoryRepository.countPendingByEditType()) {
            pendingCounts.put((EditType) row[0], (Long) row[1]);
        }

        // 이름에 버전 번호가 들어가므로 먼저 저장해 번호를 받는다
        FaqVersion version = faqVersionRepository.saveAndFlush(FaqVersion.builder()
                .versionName("")
                .versionType(versionType)
                .totalFaqCount((int) faqRepository.countByIsActiveTrue())
                .createdCount(pendingCounts.getOrDefault(EditType.CREATE, 0L).intValue())
                .expandedCount(pendingCounts.getOrDefault(EditType.EXPAND, 0L).intValue())
                .manualCount(pendingCounts.getOrDefault(EditType.MANUAL, 0L).intValue())
                .deletedCount(pendingCounts.getOrDefault(EditType.DELETE, 0L).intValue())
                .previousVersion(previous)
                .createdBy(createdBy)
                .build());
        version.rename("v" + version.getVersionId() + "_" + LocalDate.now(NAME_ZONE).format(NAME_DATE));

        int assigned = faqEditHistoryRepository.assignPendingToVersion(version);
        int copied = faqVersionItemRepository.snapshotActiveFaqs(version.getVersionId());

        log.info("FAQ 버전 생성: versionId={}, name={}, type={}, 변경 {}건, FAQ {}건",
                version.getVersionId(), version.getVersionName(), versionType, assigned, copied);
        return version;
    }

    // 관리자가 직접 버전 만들기, 다운로드 없이 현재 상태를 저장한다
    @Transactional
    public FaqVersionDetailResponse createManualVersion(String email) {
        FaqVersion version = createVersion(VersionType.MANUAL, findUser(email));
        return getVersionDetail(version.getVersionId());
    }

    // 이전 버전으로 복원
    // 사본과 현재 FAQ를 FAQ 번호로 맞대어, 사본에 있는데 삭제된 FAQ는 되살리고(CREATE), 내용이 다르면 사본 내용으로 바꾸고(MANUAL),
    // 사본에 없는 활성 FAQ는 삭제(DELETE)한다. 그 버전 뒤에 반영된 후보는 반영을 취소해 대기 상태로 돌린다
    // 바뀐 것이 있으면 복원 결과를 새 버전(RESTORE)으로 남기고, 없으면 버전을 만들지 않는다
    @Transactional
    public FaqVersionRestoreResponse restoreVersion(Long versionId, String email) {
        User user = findUser(email);
        FaqVersion target = getVersion(versionId);
        String reason = target.getVersionName() + " 버전으로 복원";

        Map<Long, FaqVersionItem> snapshot = faqVersionItemRepository.findByVersion_VersionId(versionId).stream()
                .collect(Collectors.toMap(FaqVersionItem::getFaqId, Function.identity()));
        Map<Long, Faq> snapshotFaqs = faqRepository.findAllById(snapshot.keySet()).stream()
                .collect(Collectors.toMap(Faq::getFaqId, Function.identity()));

        int updated = 0;
        int reactivated = 0;
        for (FaqVersionItem item : snapshot.values()) {
            Faq faq = snapshotFaqs.get(item.getFaqId());
            if (faq == null) {
                // FAQ 행을 직접 지운 경우라 되살릴 수 없다
                log.warn("복원할 FAQ 없음: versionId={}, faqId={}", versionId, item.getFaqId());
                continue;
            }
            boolean wasActive = Boolean.TRUE.equals(faq.getIsActive());
            if (wasActive && sameContent(FaqContent.of(faq), FaqContent.of(item))) continue;

            String beforeQuestion = faq.getQuestion();
            String beforeAnswer = faq.getAnswer();
            List<String> beforeKeywords = parseKeywords(faq.getKeywords());
            faq.restoreFrom(item.getQuestion(), item.getAnswer(), item.getKeywords(), item.getQType(), item.getCategory());

            // 되살린 FAQ는 등록, 내용만 되돌린 FAQ는 수정으로 남긴다
            FaqEditHistory.FaqEditHistoryBuilder history = FaqEditHistory.builder()
                    .faq(faq)
                    .editedBy(user)
                    .editType(wasActive ? EditType.MANUAL : EditType.CREATE)
                    .afterQuestion(faq.getQuestion())
                    .afterAnswer(faq.getAnswer())
                    .afterKeywords(parseKeywords(faq.getKeywords()))
                    .editReason(reason);
            if (wasActive) {
                history.beforeQuestion(beforeQuestion).beforeAnswer(beforeAnswer).beforeKeywords(beforeKeywords);
                updated++;
            } else {
                reactivated++;
            }
            faqEditHistoryRepository.save(history.build());
        }

        int deleted = 0;
        for (Faq faq : faqRepository.findByIsActiveTrue()) {
            if (snapshot.containsKey(faq.getFaqId())) continue;
            faq.deactivate();
            faqEditHistoryRepository.save(FaqEditHistory.builder()
                    .faq(faq)
                    .editedBy(user)
                    .editType(EditType.DELETE)
                    .beforeQuestion(faq.getQuestion())
                    .beforeAnswer(faq.getAnswer())
                    .beforeKeywords(parseKeywords(faq.getKeywords()))
                    .editReason(reason)
                    .build());
            deleted++;
        }

        // 그 버전을 만든 뒤에 반영된 후보는 복원으로 반영 결과가 사라지므로 다시 검토할 수 있게 되돌린다
        List<FaqCandidate> reverted = faqActionLogRepository
                .findByActionSinceWithCandidateStatus(FaqActionLog.Action.APPLY, target.getCreatedAt(), ReviewStatus.APPLIED)
                .stream()
                .map(FaqActionLog::getCandidate)
                .distinct()
                .toList();
        for (FaqCandidate candidate : reverted) {
            candidate.revertToPending();
            faqActionLogRepository.save(FaqActionLog.builder()
                    .candidate(candidate)
                    .actedBy(user)
                    .action(FaqActionLog.Action.REVERT)
                    .beforeStatus(ReviewStatus.APPLIED)
                    .afterStatus(ReviewStatus.PENDING)
                    .note(target.getVersionName() + " 버전 복원으로 반영 취소")
                    .build());
        }

        FaqVersion newVersion = null;
        if (updated + reactivated + deleted + reverted.size() > 0) {
            // 새 버전의 사본은 DB에서 바로 복사하므로 바뀐 내용을 먼저 반영한다
            faqRepository.flush();
            newVersion = createVersion(VersionType.RESTORE, user);
        }
        log.info("FAQ 버전 복원: versionId={}, 수정 {}건, 삭제 {}건, 되살림 {}건, 후보 되돌림 {}건, 새 버전 {}",
                versionId, updated, deleted, reactivated, reverted.size(),
                newVersion != null ? newVersion.getVersionId() : null);

        return FaqVersionRestoreResponse.builder()
                .restoredVersionId(target.getVersionId())
                .restoredVersionName(target.getVersionName())
                .newVersionId(newVersion != null ? newVersion.getVersionId() : null)
                .newVersionName(newVersion != null ? newVersion.getVersionName() : null)
                .updatedCount(updated)
                .deletedCount(deleted)
                .reactivatedCount(reactivated)
                .revertedCandidateCount(reverted.size())
                .build();
    }

    // 두 버전 비교, targetId가 없으면 기준 버전과 현재 운영 FAQ를 비교한다
    // 추가·삭제는 어떤 FAQ인지만, 변경은 바뀐 칸의 전후 값만 담는다
    @Transactional(readOnly = true)
    public FaqVersionCompareResponse compareVersions(Long baseId, Long targetId) {
        FaqVersion base = getVersion(baseId);
        FaqVersion target = targetId != null ? getVersion(targetId) : null;

        Map<Long, FaqContent> from = versionContents(baseId);
        Map<Long, FaqContent> to = target != null
                ? versionContents(targetId)
                : faqRepository.findByIsActiveTrue().stream()
                        .collect(Collectors.toMap(Faq::getFaqId, FaqContent::of, (a, b) -> a, TreeMap::new));

        List<FaqVersionCompareResponse.FaqItem> added = to.values().stream()
                .filter(c -> !from.containsKey(c.faqId()))
                .map(this::toFaqItem)
                .toList();
        List<FaqVersionCompareResponse.FaqItem> deleted = from.values().stream()
                .filter(c -> !to.containsKey(c.faqId()))
                .map(this::toFaqItem)
                .toList();
        List<FaqVersionCompareResponse.ChangedItem> changed = new ArrayList<>();
        for (FaqContent after : to.values()) {
            FaqContent before = from.get(after.faqId());
            if (before == null || sameContent(before, after)) continue;
            changed.add(FaqVersionCompareResponse.ChangedItem.builder()
                    .faqId(after.faqId())
                    .standardQuestion(after.question())
                    .changes(fieldChanges(before, after))
                    .build());
        }

        return FaqVersionCompareResponse.builder()
                .baseVersionId(base.getVersionId())
                .baseVersionName(base.getVersionName())
                .targetVersionId(target != null ? target.getVersionId() : null)
                .targetVersionName(target != null ? target.getVersionName() : null)
                .addedCount(added.size())
                .deletedCount(deleted.size())
                .changedCount(changed.size())
                .added(added)
                .deleted(deleted)
                .changed(changed)
                .build();
    }

    // 버전 목록 조회, 최신순 페이징
    @Transactional(readOnly = true)
    public FaqVersionListResponse getVersions(int page, int size) {
        Page<FaqVersion> versionPage = faqVersionRepository.findAllByOrderByVersionIdDesc(PageRequest.of(page, size));
        Long latestId = latestVersionId();

        List<FaqVersionListResponse.VersionItem> items = versionPage.getContent().stream()
                .map(v -> FaqVersionListResponse.VersionItem.builder()
                        .versionId(v.getVersionId())
                        .versionName(v.getVersionName())
                        .versionType(v.getVersionType())
                        .totalFaqCount(v.getTotalFaqCount())
                        .createdCount(v.getCreatedCount())
                        .expandedCount(v.getExpandedCount())
                        .isLatest(v.getVersionId().equals(latestId))
                        // 탈퇴한 사용자가 만든 버전은 생성자 정보 없이 반환
                        .createdBy(v.getCreatedBy() != null ? v.getCreatedBy().getEmail() : null)
                        .createdAt(v.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return FaqVersionListResponse.builder()
                .totalCount(versionPage.getTotalElements())
                .versions(items)
                .totalPages(versionPage.getTotalPages())
                .currentPage(page)
                .size(size)
                .build();
    }

    // 버전 상세 조회, 이 버전에 묶인 변경 목록 포함
    @Transactional(readOnly = true)
    public FaqVersionDetailResponse getVersionDetail(Long versionId) {
        FaqVersion version = getVersion(versionId);

        List<FaqVersionDetailResponse.ChangeItem> changes =
                faqEditHistoryRepository.findByVersion_VersionIdOrderByHistoryIdAsc(versionId).stream()
                        .map(this::toChangeItem)
                        .collect(Collectors.toList());

        return FaqVersionDetailResponse.builder()
                .versionId(version.getVersionId())
                .versionName(version.getVersionName())
                .versionType(version.getVersionType())
                .totalFaqCount(version.getTotalFaqCount())
                .createdCount(version.getCreatedCount())
                .expandedCount(version.getExpandedCount())
                .manualCount(version.getManualCount())
                .deletedCount(version.getDeletedCount())
                .previousVersionId(version.getPreviousVersion() != null
                        ? version.getPreviousVersion().getVersionId() : null)
                .isLatest(version.getVersionId().equals(latestVersionId()))
                .createdBy(version.getCreatedBy() != null ? version.getCreatedBy().getEmail() : null)
                .createdAt(version.getCreatedAt())
                .changes(changes)
                .build();
    }

    // 버전 단건 조회, 없으면 404
    @Transactional(readOnly = true)
    public FaqVersion getVersion(Long versionId) {
        return faqVersionRepository.findById(versionId)
                .orElseThrow(() -> new CustomException(ErrorCode.FAQ_VERSION_NOT_FOUND));
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    // 비교·복원에 쓰는 FAQ 내용, 버전 사본과 운영 FAQ를 같은 모양으로 맞춘다 (키워드는 저장된 JSON 문자열 그대로)
    private record FaqContent(Long faqId, String question, String answer, String keywords,
                              Integer qType, String category) {
        static FaqContent of(Faq faq) {
            return new FaqContent(faq.getFaqId(), faq.getQuestion(), faq.getAnswer(), faq.getKeywords(),
                    faq.getQType(), faq.getCategory());
        }

        static FaqContent of(FaqVersionItem item) {
            return new FaqContent(item.getFaqId(), item.getQuestion(), item.getAnswer(), item.getKeywords(),
                    item.getQType(), item.getCategory());
        }
    }

    private Map<Long, FaqContent> versionContents(Long versionId) {
        return faqVersionItemRepository.findByVersion_VersionId(versionId).stream()
                .collect(Collectors.toMap(FaqVersionItem::getFaqId, FaqContent::of, (a, b) -> a, TreeMap::new));
    }

    private static boolean sameContent(FaqContent a, FaqContent b) {
        return Objects.equals(a.question(), b.question())
                && Objects.equals(a.answer(), b.answer())
                && Objects.equals(a.keywords(), b.keywords())
                && Objects.equals(a.qType(), b.qType())
                && Objects.equals(a.category(), b.category());
    }

    // 바뀐 칸만 전후 값으로 담는다, 칸 이름은 FAQ 응답의 필드 이름과 맞춘다
    private List<FaqVersionCompareResponse.FieldChange> fieldChanges(FaqContent before, FaqContent after) {
        Map<String, Object[]> fields = new LinkedHashMap<>();
        fields.put("standardQuestion", new Object[]{before.question(), after.question()});
        fields.put("answer", new Object[]{before.answer(), after.answer()});
        fields.put("keywords", new Object[]{before.keywords(), after.keywords()});
        fields.put("qType", new Object[]{before.qType(), after.qType()});
        fields.put("category", new Object[]{before.category(), after.category()});

        List<FaqVersionCompareResponse.FieldChange> changes = new ArrayList<>();
        fields.forEach((field, values) -> {
            if (Objects.equals(values[0], values[1])) return;
            boolean isKeywords = field.equals("keywords");
            changes.add(FaqVersionCompareResponse.FieldChange.builder()
                    .field(field)
                    .before(isKeywords ? parseKeywords((String) values[0]) : values[0])
                    .after(isKeywords ? parseKeywords((String) values[1]) : values[1])
                    .build());
        });
        return changes;
    }

    private FaqVersionCompareResponse.FaqItem toFaqItem(FaqContent content) {
        return FaqVersionCompareResponse.FaqItem.builder()
                .faqId(content.faqId())
                .qType(content.qType())
                .category(content.category())
                .standardQuestion(content.question())
                .build();
    }

    // JSON 문자열로 저장된 키워드를 리스트로 변환, 없거나 실패하면 빈 리스트
    private List<String> parseKeywords(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("키워드 파싱 실패: {}", json);
            return List.of();
        }
    }

    private Long latestVersionId() {
        return faqVersionRepository.findTopByOrderByVersionIdDesc()
                .map(FaqVersion::getVersionId)
                .orElse(null);
    }

    // 삭제 이력은 수정 후 값이 없으므로 수정 전 질문을 보여준다
    private FaqVersionDetailResponse.ChangeItem toChangeItem(FaqEditHistory history) {
        return FaqVersionDetailResponse.ChangeItem.builder()
                .historyId(history.getHistoryId())
                .faqId(history.getFaq().getFaqId())
                .editType(history.getEditType())
                .qType(history.getFaq().getQType())
                .standardQuestion(history.getAfterQuestion() != null
                        ? history.getAfterQuestion() : history.getBeforeQuestion())
                .build();
    }
}
