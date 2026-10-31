package com.breathAI.ttobagi_server.domain.faq.service;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion.VersionType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersionItem;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqEditHistoryRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionItemRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionItemRepository.ExportRow;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqVersionRepository;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// FAQ 다운로드 파일 생성
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqExportService {

    // 파일 이름의 날짜는 운영자가 보는 값이라 한국 시간 기준으로 붙인다
    private static final ZoneId FILE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 엑셀이 UTF-8로 읽도록 파일 맨 앞에 붙이는 표시
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("ID", "카테고리", "질문", "답변", "키워드", "유사어")
            .get();

    private static final CSVFormat KEYWORD_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("ID", "카테고리", "질문", "키워드")
            .get();

    private static final CSVFormat NEW_KEYWORD_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("ID", "카테고리", "질문", "새 키워드")
            .get();

    private final FaqVersionService faqVersionService;
    private final FaqVersionRepository faqVersionRepository;
    private final FaqVersionItemRepository faqVersionItemRepository;
    private final FaqEditHistoryRepository faqEditHistoryRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public record ExportFile(String fileName, byte[] content) {}

    // FAQ를 CSV 파일로 만든다
    // versionId가 있으면 그 버전을, 없으면 최신 내용을 내려준다
    @Transactional
    public ExportFile export(Long versionId, String email) {
        FaqVersion version = versionId != null
                ? faqVersionService.getVersion(versionId)
                : resolveLatestVersion(email);

        List<ExportRow> rows = faqVersionItemRepository.findExportRows(version.getVersionId());
        String fileName = "faq_v" + version.getVersionId() + "_"
                + LocalDate.now(FILE_ZONE).format(FILE_DATE) + ".csv";

        List<List<Object>> records = rows.stream()
                .map(row -> Arrays.<Object>asList(row.getFaqId(), row.getCategoryName(), row.getQuestion(),
                        row.getAnswer(), joinKeywords(row.getKeywords()), joinKeywords(row.getSynonyms())))
                .toList();

        log.info("FAQ 다운로드: versionId={}, FAQ {}건, file={}", version.getVersionId(), rows.size(), fileName);
        return new ExportFile(fileName, toCsv(CSV_FORMAT, records));
    }

    // 키워드만 CSV로 만든다, 버전은 FAQ 다운로드와 같은 규칙으로 정한다
    // sinceVersionId가 있으면 그 버전 이후 새로 생긴 키워드만 (그 뒤에 생긴 FAQ는 키워드 전체)
    @Transactional
    public ExportFile exportKeywords(Long sinceVersionId, String email) {
        // 없는 기준 버전이면 새 버전을 만들기 전에 거부한다
        FaqVersion since = sinceVersionId != null ? faqVersionService.getVersion(sinceVersionId) : null;
        FaqVersion latest = resolveLatestVersion(email);
        List<ExportRow> rows = faqVersionItemRepository.findExportRows(latest.getVersionId());
        String date = LocalDate.now(FILE_ZONE).format(FILE_DATE);

        if (since == null) {
            List<List<Object>> records = rows.stream()
                    .map(row -> Arrays.<Object>asList(row.getFaqId(), row.getCategoryName(), row.getQuestion(),
                            joinKeywords(row.getKeywords())))
                    .toList();
            String fileName = "faq_keywords_v" + latest.getVersionId() + "_" + date + ".csv";
            log.info("키워드 다운로드: versionId={}, FAQ {}건, file={}", latest.getVersionId(), records.size(), fileName);
            return new ExportFile(fileName, toCsv(KEYWORD_FORMAT, records));
        }

        // 기준 버전 사본의 FAQ별 키워드, 글자가 완전히 같으면 같은 키워드로 본다
        Map<Long, Set<String>> sinceKeywords = faqVersionItemRepository.findByVersion_VersionId(since.getVersionId())
                .stream()
                .collect(Collectors.toMap(FaqVersionItem::getFaqId, i -> new HashSet<>(parseKeywords(i.getKeywords())),
                        (a, b) -> a));
        List<List<Object>> records = new ArrayList<>();
        for (ExportRow row : rows) {
            Set<String> before = sinceKeywords.getOrDefault(row.getFaqId(), Set.of());
            List<String> added = parseKeywords(row.getKeywords()).stream()
                    .filter(keyword -> !before.contains(keyword))
                    .distinct()
                    .toList();
            if (added.isEmpty()) continue;
            records.add(Arrays.asList(row.getFaqId(), row.getCategoryName(), row.getQuestion(), String.join(", ", added)));
        }
        String fileName = "faq_keywords_v" + since.getVersionId() + "_to_v" + latest.getVersionId() + "_" + date + ".csv";
        log.info("새 키워드 다운로드: since={}, versionId={}, FAQ {}건, file={}",
                since.getVersionId(), latest.getVersionId(), records.size(), fileName);
        return new ExportFile(fileName, toCsv(NEW_KEYWORD_FORMAT, records));
    }

    // 마지막 버전 이후 변경이 있거나 버전이 하나도 없으면 새 버전을 만들고, 아니면 마지막 버전을 쓴다
    private FaqVersion resolveLatestVersion(String email) {
        FaqVersion latest = faqVersionRepository.findTopByOrderByVersionIdDesc().orElse(null);
        if (latest != null && !faqEditHistoryRepository.existsByVersionIsNull()) {
            return latest;
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return faqVersionService.createVersion(VersionType.DOWNLOAD, user);
    }

    private byte[] toCsv(CSVFormat format, List<List<Object>> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), format)) {
            for (List<Object> record : records) {
                printer.printRecord(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // JSON 배열로 저장된 키워드·유사어를 쉼표로 이어 쓴다, 실패 시 빈 칸
    private String joinKeywords(String json) {
        return String.join(", ", parseKeywords(json));
    }

    // JSON 배열로 저장된 키워드·유사어를 리스트로 바꾼다, 없거나 실패하면 빈 리스트
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
