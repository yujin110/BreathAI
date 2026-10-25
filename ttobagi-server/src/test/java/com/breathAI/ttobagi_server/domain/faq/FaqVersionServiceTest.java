package com.breathAI.ttobagi_server.domain.faq;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqEditHistoryResponse;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqListResponse;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqVersionDetailResponse;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqVersionListResponse;
import com.breathAI.ttobagi_server.domain.faq.entity.Faq;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory.EditType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion.VersionType;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqEditHistoryRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionItemRepository;
import com.breathAI.ttobagi_server.domain.faq.service.FaqService;
import com.breathAI.ttobagi_server.domain.faq.service.FaqVersionService;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

// FAQ 버전 생성과 조회 검증
// 테스트 전용 DB(Testcontainers)에서 실행하고 테스트가 끝나면 모두 롤백된다
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FaqVersionServiceTest {

    private static final String MARK = "버전테스트-";

    @Autowired private FaqVersionService faqVersionService;
    @Autowired private FaqService faqService;
    @Autowired private FaqRepository faqRepository;
    @Autowired private FaqEditHistoryRepository faqEditHistoryRepository;
    @Autowired private FaqVersionItemRepository faqVersionItemRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private User admin;
    private Faq created;
    private Faq edited;
    private Faq deleted;

    // 이미 DB에 쌓여 있던 미확정 변경 건수 (테스트가 추가한 것과 구분하기 위함)
    private Map<EditType, Long> pendingBefore;

    @BeforeEach
    void setUp() {
        pendingBefore = pendingCounts();

        admin = userRepository.save(User.builder()
                .email("version-test@seoulmetro.co.kr")
                .password("encoded")
                .role(User.Role.ADMIN)
                .build());

        created = saveFaq("신규 등록", 200);
        edited = saveFaq("직접 수정", 188);
        deleted = saveFaq("삭제", 204);

        saveHistory(created, EditType.CREATE, null, created.getQuestion());
        saveHistory(edited, EditType.MANUAL, MARK + "수정 전", edited.getQuestion());
        saveHistory(edited, EditType.EXPAND, edited.getQuestion(), edited.getQuestion());
        deleted.deactivate();
        saveHistory(deleted, EditType.DELETE, deleted.getQuestion(), null);
        em.flush();
    }

    @Test
    @DisplayName("버전을 만들면 미확정 변경이 묶이고 활성 FAQ가 사본으로 남는다")
    void createVersionBindsPendingChangesAndSnapshotsActiveFaqs() {
        long activeCount = faqRepository.countByIsActiveTrue();

        FaqVersion version = faqVersionService.createVersion(VersionType.DOWNLOAD, admin);
        em.flush();
        em.clear();

        // 변경 유형별 건수
        assertEquals(pendingBefore.getOrDefault(EditType.CREATE, 0L) + 1, version.getCreatedCount().longValue());
        assertEquals(pendingBefore.getOrDefault(EditType.EXPAND, 0L) + 1, version.getExpandedCount().longValue());
        assertEquals(pendingBefore.getOrDefault(EditType.MANUAL, 0L) + 1, version.getManualCount().longValue());
        assertEquals(pendingBefore.getOrDefault(EditType.DELETE, 0L) + 1, version.getDeletedCount().longValue());
        assertEquals(activeCount, version.getTotalFaqCount().longValue());

        // 이름은 v번호_날짜
        assertTrue(version.getVersionName().matches("v" + version.getVersionId() + "_\\d{8}"), version.getVersionName());

        // 미확정 변경이 남지 않는다
        assertTrue(pendingCounts().isEmpty());

        // 사본은 활성 FAQ만 담는다
        assertEquals(activeCount, faqVersionItemRepository.countByVersion_VersionId(version.getVersionId()));

        // 상세 조회에 테스트가 만든 변경 4건이 유형과 함께 나온다
        FaqVersionDetailResponse detail = faqVersionService.getVersionDetail(version.getVersionId());
        assertTrue(detail.getIsLatest());
        List<FaqVersionDetailResponse.ChangeItem> mine = detail.getChanges().stream()
                .filter(c -> c.getStandardQuestion() != null && c.getStandardQuestion().startsWith(MARK))
                .collect(Collectors.toList());
        assertEquals(List.of(EditType.CREATE, EditType.MANUAL, EditType.EXPAND, EditType.DELETE),
                mine.stream().map(FaqVersionDetailResponse.ChangeItem::getEditType).collect(Collectors.toList()));
        // 삭제 이력은 수정 후 값이 없으므로 수정 전 질문이 나온다
        assertEquals(deleted.getQuestion(), mine.get(3).getStandardQuestion());
        assertEquals(204, mine.get(3).getQType());

        // 변경 이력 조회에 버전 번호가 붙는다
        FaqEditHistoryResponse histories = faqService.getFaqHistory(0, 4, null, null);
        assertTrue(histories.getHistories().stream().allMatch(h -> version.getVersionId().equals(h.getVersionId())));
    }

    @Test
    @DisplayName("버전을 지정해 조회하면 그 시점의 FAQ가 나온다")
    void listByVersionReturnsFaqsAsOfThatVersion() {
        FaqVersion first = faqVersionService.createVersion(VersionType.UPLOAD, admin);
        em.flush();

        // 버전 생성 뒤에 FAQ를 고치고 새로 등록한다
        edited.update(MARK + "버전 이후에 수정됨", null, null, null);
        Faq later = saveFaq("버전 이후 등록", 200);
        saveHistory(later, EditType.CREATE, null, later.getQuestion());
        em.flush();
        em.clear();

        // 버전을 지정하면 고치기 전 내용이 나오고, 이후 등록된 FAQ는 없다
        FaqListResponse atVersion = faqService.getFaqList(first.getVersionId(), 0, 50, null, MARK);
        assertEquals(first.getVersionId(), atVersion.getVersionId());
        assertEquals(first.getVersionName(), atVersion.getVersionName());
        List<String> questionsAtVersion = questions(atVersion);
        assertTrue(questionsAtVersion.contains(MARK + "직접 수정"));
        assertFalse(questionsAtVersion.contains(MARK + "버전 이후에 수정됨"));
        assertFalse(questionsAtVersion.contains(MARK + "버전 이후 등록"));
        // 삭제된 FAQ는 사본에 들어가지 않는다
        assertFalse(questionsAtVersion.contains(MARK + "삭제"));

        // 버전을 지정하지 않으면 현재 운영 FAQ가 나오고 버전 정보는 비어 있다
        FaqListResponse current = faqService.getFaqList(null, 0, 50, null, MARK);
        assertNull(current.getVersionId());
        assertNull(current.getVersionName());
        List<String> currentQuestions = questions(current);
        assertTrue(currentQuestions.contains(MARK + "버전 이후에 수정됨"));
        assertTrue(currentQuestions.contains(MARK + "버전 이후 등록"));

        // 다음 버전은 직전 버전을 가리키고, 그 사이의 변경만 센다
        FaqVersion second = faqVersionService.createVersion(VersionType.DOWNLOAD, admin);
        em.flush();
        em.clear();
        FaqVersionDetailResponse secondDetail = faqVersionService.getVersionDetail(second.getVersionId());
        assertEquals(first.getVersionId(), secondDetail.getPreviousVersionId());
        assertEquals(1, secondDetail.getCreatedCount());
        assertEquals(0, secondDetail.getManualCount());
        assertEquals(1, secondDetail.getChanges().size());
        assertTrue(secondDetail.getIsLatest());
        assertFalse(faqVersionService.getVersionDetail(first.getVersionId()).getIsLatest());

        // 목록은 최신순이고 최신 버전만 isLatest
        FaqVersionListResponse list = faqVersionService.getVersions(0, 2);
        assertEquals(second.getVersionId(), list.getVersions().get(0).getVersionId());
        assertTrue(list.getVersions().get(0).getIsLatest());
        assertEquals(first.getVersionId(), list.getVersions().get(1).getVersionId());
        assertFalse(list.getVersions().get(1).getIsLatest());
        assertEquals("version-test@seoulmetro.co.kr", list.getVersions().get(0).getCreatedBy());
    }

    @Test
    @DisplayName("없는 버전을 조회하면 예외가 난다")
    void unknownVersionThrows() {
        assertThrows(CustomException.class, () -> faqVersionService.getVersionDetail(-1L));
        assertThrows(CustomException.class, () -> faqService.getFaqList(-1L, 0, 10, null, null));
    }

    private List<String> questions(FaqListResponse response) {
        return response.getFaqList().stream()
                .map(FaqListResponse.FaqItem::getStandardQuestion)
                .collect(Collectors.toList());
    }

    private Faq saveFaq(String name, int qType) {
        return faqRepository.save(Faq.builder()
                .question(MARK + name)
                .answer("답변")
                .keywords("[\"테스트\"]")
                .qType(qType)
                .createdBy(admin)
                .build());
    }

    private void saveHistory(Faq faq, EditType editType, String before, String after) {
        faqEditHistoryRepository.save(FaqEditHistory.builder()
                .faq(faq)
                .editedBy(admin)
                .editType(editType)
                .beforeQuestion(before)
                .afterQuestion(after)
                .build());
    }

    private Map<EditType, Long> pendingCounts() {
        return faqEditHistoryRepository.countPendingByEditType().stream()
                .collect(Collectors.toMap(row -> (EditType) row[0], row -> (Long) row[1]));
    }
}
