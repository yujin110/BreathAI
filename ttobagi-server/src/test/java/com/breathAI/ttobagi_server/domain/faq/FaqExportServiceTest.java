package com.breathAI.ttobagi_server.domain.faq;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.faq.entity.Faq;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory.EditType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion.VersionType;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqEditHistoryRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionRepository;
import com.breathAI.ttobagi_server.domain.faq.service.FaqExportService;
import com.breathAI.ttobagi_server.domain.faq.service.FaqExportService.ExportFile;
import com.breathAI.ttobagi_server.domain.faq.service.FaqVersionService;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import jakarta.persistence.EntityManager;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// FAQ 다운로드 검증
// 테스트 전용 DB(Testcontainers)에서 실행하고 테스트가 끝나면 모두 롤백된다
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FaqExportServiceTest {

    private static final String MARK = "다운로드테스트-";
    private static final String EMAIL = "export-test@seoulmetro.co.kr";
    private static final String TRICKY_ANSWER = "<p>쉼표, \"따옴표\"</p>\n<p>줄바꿈</p>";

    @Autowired private FaqExportService faqExportService;
    @Autowired private FaqVersionService faqVersionService;
    @Autowired private FaqRepository faqRepository;
    @Autowired private FaqEditHistoryRepository faqEditHistoryRepository;
    @Autowired private FaqVersionRepository faqVersionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(User.builder()
                .email(EMAIL)
                .password("encoded")
                .role(User.Role.ADMIN)
                .build());
    }

    @Test
    @DisplayName("버전이 하나도 없으면 변경이 없어도 첫 버전을 만들어 내려준다")
    void createsFirstVersionWhenNoneExists() {
        // 버전과 미확정 변경이 모두 없는 DB에서만 확인할 수 있다
        assumeTrue(faqVersionRepository.count() == 0 && !faqEditHistoryRepository.existsByVersionIsNull());

        ExportFile file = faqExportService.export(null, EMAIL);

        FaqVersion version = faqVersionRepository.findTopByOrderByVersionIdDesc().orElseThrow();
        assertEquals(1, faqVersionRepository.count());
        assertEquals(VersionType.DOWNLOAD, version.getVersionType());
        assertEquals(expectedFileName(version), file.fileName());
        assertEquals(faqRepository.countByIsActiveTrue(), parse(file).size());
    }

    @Test
    @DisplayName("변경이 있으면 새 버전을 만들고 그 내용을 CSV로 내려준다")
    void createsVersionWhenPendingChangesExist() {
        Faq created = saveFaq("신규 등록", 200, TRICKY_ANSWER, "[\"환불\",\"정기권\"]");
        Faq noKeywords = saveFaq("키워드 없음", 188, "답변", null);
        Faq deleted = saveFaq("삭제", 204, "답변", null);
        saveHistory(created, EditType.CREATE, null, created.getQuestion());
        deleted.deactivate();
        saveHistory(deleted, EditType.DELETE, deleted.getQuestion(), null);
        em.flush();
        long versionsBefore = faqVersionRepository.count();

        ExportFile file = faqExportService.export(null, EMAIL);

        // 새 DOWNLOAD 버전이 만들어지고 미확정 변경이 남지 않는다
        FaqVersion version = faqVersionRepository.findTopByOrderByVersionIdDesc().orElseThrow();
        assertEquals(versionsBefore + 1, faqVersionRepository.count());
        assertEquals(VersionType.DOWNLOAD, version.getVersionType());
        assertFalse(faqEditHistoryRepository.existsByVersionIsNull());
        assertEquals(expectedFileName(version), file.fileName());

        // 엑셀이 UTF-8로 읽도록 BOM으로 시작한다
        byte[] content = file.content();
        assertEquals((byte) 0xEF, content[0]);
        assertEquals((byte) 0xBB, content[1]);
        assertEquals((byte) 0xBF, content[2]);
        assertTrue(text(file).startsWith("ID,카테고리,질문,답변,키워드,유사어\r\n"));

        // 활성 FAQ 전체가 ID 순으로 들어간다
        List<CSVRecord> records = parse(file);
        assertEquals(faqRepository.countByIsActiveTrue(), records.size());
        List<Long> ids = records.stream().map(r -> Long.valueOf(r.get("ID"))).collect(Collectors.toList());
        assertEquals(ids.stream().sorted().collect(Collectors.toList()), ids);

        // 답변은 그대로, 키워드는 쉼표로, 카테고리는 마스터의 이름으로 나온다
        CSVRecord createdRow = find(records, created);
        assertEquals(created.getQuestion(), createdRow.get("질문"));
        assertEquals(TRICKY_ANSWER, createdRow.get("답변"));
        assertEquals("환불, 정기권", createdRow.get("키워드"));
        assertEquals(categoryName(200), createdRow.get("카테고리"));
        assertEquals("", find(records, noKeywords).get("키워드"));

        // 삭제된 FAQ는 들어가지 않는다
        assertFalse(ids.contains(deleted.getFaqId()));
    }

    @Test
    @DisplayName("변경이 없으면 새 버전을 만들지 않고 마지막 버전을 내려준다")
    void reusesLatestVersionWhenNothingChanged() {
        FaqVersion latest = faqVersionService.createVersion(VersionType.UPLOAD, admin);
        em.flush();
        long versionsBefore = faqVersionRepository.count();

        ExportFile file = faqExportService.export(null, EMAIL);

        assertEquals(versionsBefore, faqVersionRepository.count());
        assertEquals(expectedFileName(latest), file.fileName());
        assertEquals(latest.getTotalFaqCount().intValue(), parse(file).size());
    }

    @Test
    @DisplayName("버전을 지정하면 그 시점의 FAQ를 내려주고 새 버전을 만들지 않는다")
    void exportsGivenVersionAsOfThatTime() {
        Faq edited = saveFaq("수정 전", 200, "답변", null);
        em.flush();
        FaqVersion first = faqVersionService.createVersion(VersionType.UPLOAD, admin);
        em.flush();

        // 버전 생성 뒤에 FAQ를 고치고 새로 등록한다
        edited.update(MARK + "수정 후", null, null, null, null);
        Faq later = saveFaq("버전 이후 등록", 200, "답변", null);
        saveHistory(later, EditType.CREATE, null, later.getQuestion());
        em.flush();
        long versionsBefore = faqVersionRepository.count();

        ExportFile file = faqExportService.export(first.getVersionId(), EMAIL);

        assertEquals(versionsBefore, faqVersionRepository.count());
        assertTrue(faqEditHistoryRepository.existsByVersionIsNull());
        assertEquals(expectedFileName(first), file.fileName());
        List<CSVRecord> records = parse(file);
        assertEquals(MARK + "수정 전", find(records, edited).get("질문"));
        assertTrue(records.stream().noneMatch(r -> r.get("ID").equals(String.valueOf(later.getFaqId()))));
    }

    @Test
    @DisplayName("없는 버전을 지정하면 예외가 난다")
    void unknownVersionThrows() {
        assertThrows(CustomException.class, () -> faqExportService.export(-1L, EMAIL));
    }

    private String expectedFileName(FaqVersion version) {
        return "faq_v" + version.getVersionId() + "_"
                + LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".csv";
    }

    // BOM을 뗀 본문
    private String text(ExportFile file) {
        return new String(file.content(), 3, file.content().length - 3, StandardCharsets.UTF_8);
    }

    private List<CSVRecord> parse(ExportFile file) {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = format.parse(new StringReader(text(file)))) {
            return parser.getRecords();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CSVRecord find(List<CSVRecord> records, Faq faq) {
        return records.stream()
                .filter(r -> r.get("ID").equals(String.valueOf(faq.getFaqId())))
                .findFirst()
                .orElseThrow();
    }

    // 마스터에 없는 코드면 빈 칸으로 나온다
    private String categoryName(int qType) {
        List<?> names = em.createNativeQuery("SELECT q_disp_name FROM bronze_cate_info WHERE q_type = :qType")
                .setParameter("qType", qType)
                .getResultList();
        return names.isEmpty() ? "" : (String) names.get(0);
    }

    private Faq saveFaq(String name, int qType, String answer, String keywords) {
        return faqRepository.save(Faq.builder()
                .question(MARK + name)
                .answer(answer)
                .keywords(keywords)
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
}
