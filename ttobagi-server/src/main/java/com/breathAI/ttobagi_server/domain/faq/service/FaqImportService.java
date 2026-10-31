package com.breathAI.ttobagi_server.domain.faq.service;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.dashboard.dto.UploadErrorResponse;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisUploadReader;
import com.breathAI.ttobagi_server.domain.faq.dto.FaqImportResponse;
import com.breathAI.ttobagi_server.domain.faq.entity.Faq;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory.EditType;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion.VersionType;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqEditHistoryRepository;
import com.breathAI.ttobagi_server.domain.faq.repository.FaqRepository;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.breathAI.ttobagi_server.global.exception.UploadValidationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

// FAQ 일괄 업로드: 다운로드 양식과 같은 파일(csv·xlsx)을 읽어 없는 FAQ는 추가하고 내용이 다르면 수정한다
// 오류가 1건이라도 있으면 아무것도 저장하지 않는다 (분석 업로드와 같은 오류 형식)
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqImportService {

    static final int MAX_REPORTED_ERRORS = 100;
    static final String EDIT_REASON = "파일 일괄 업로드";
    static final String SKIP_REASON = "같은 질문의 FAQ가 여러 개라 고칠 FAQ를 정할 수 없음";
    private static final int MAX_CATEGORY_LENGTH = 100;

    private final AnalysisUploadReader uploadReader;
    private final FaqService faqService;
    private final FaqVersionService faqVersionService;
    private final FaqRepository faqRepository;
    private final FaqEditHistoryRepository faqEditHistoryRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // 파일 한 행, 열이 없으면 그 항목은 null이고 기존 값을 유지한다 (카테고리는 hasCategory로 구분)
    private record ImportRow(int row, Long id, String question, String key, String answer,
                             boolean hasCategory, String category, List<String> keywords, List<String> synonyms) {}

    private record ParsedFile(List<ImportRow> rows, List<FaqImportResponse.MergedDuplicate> merged) {}

    @Transactional
    public FaqImportResponse importFaqs(MultipartFile file, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        ParsedFile parsed = parse(uploadReader.read(file));

        Map<Integer, String> masterNames = faqRepository.findLatestCategories().stream()
                .collect(Collectors.toMap(FaqRepository.CategoryRow::getCode, FaqRepository.CategoryRow::getName));
        // 질문(공백 정리)별 활성 FAQ
        Map<String, List<Faq>> activeByKey = faqRepository.findByIsActiveTrue().stream()
                .collect(Collectors.groupingBy(f -> questionKey(f.getQuestion()), LinkedHashMap::new, Collectors.toList()));

        List<FaqImportResponse.AddedItem> added = new ArrayList<>();
        List<FaqImportResponse.UpdatedItem> updated = new ArrayList<>();
        List<FaqImportResponse.SkippedRow> skipped = new ArrayList<>();
        LinkedHashSet<String> newCategories = new LinkedHashSet<>();
        int unchanged = 0;

        for (ImportRow row : parsed.rows()) {
            List<Faq> candidates = activeByKey.getOrDefault(row.key(), List.of());
            if (candidates.isEmpty()) {
                Faq faq = add(row, user, newCategories);
                added.add(FaqImportResponse.AddedItem.builder()
                        .faqId(faq.getFaqId()).standardQuestion(faq.getQuestion()).build());
                continue;
            }
            Faq target = pickTarget(row, candidates, masterNames);
            if (target == null) {
                skipped.add(FaqImportResponse.SkippedRow.builder()
                        .row(row.row())
                        .standardQuestion(row.question())
                        .candidateFaqIds(candidates.stream().map(Faq::getFaqId).sorted().toList())
                        .reason(SKIP_REASON)
                        .build());
                continue;
            }
            List<String> changed = changedFields(row, target, masterNames);
            if (changed.isEmpty()) {
                unchanged++;
                continue;
            }
            update(row, target, changed, user, newCategories);
            updated.add(FaqImportResponse.UpdatedItem.builder()
                    .faqId(target.getFaqId()).standardQuestion(target.getQuestion()).changedFields(changed).build());
        }

        FaqVersion version = added.isEmpty() && updated.isEmpty()
                ? null : faqVersionService.createVersion(VersionType.UPLOAD, user);
        log.info("FAQ 일괄 업로드: 추가 {}건, 수정 {}건, 변경 없음 {}건, 건너뜀 {}건, 새 카테고리 {}개, versionId={}",
                added.size(), updated.size(), unchanged, skipped.size(), newCategories.size(),
                version != null ? version.getVersionId() : null);
        if (!skipped.isEmpty()) {
            log.warn("FAQ 일괄 업로드 건너뛴 행: {}", skipped.stream().map(FaqImportResponse.SkippedRow::getRow).toList());
        }

        return FaqImportResponse.builder()
                .addedCount(added.size())
                .updatedCount(updated.size())
                .unchangedCount(unchanged)
                .skippedCount(skipped.size())
                .newCategories(List.copyOf(newCategories))
                .mergedDuplicates(parsed.merged())
                .skipped(skipped)
                .versionId(version != null ? version.getVersionId() : null)
                .versionName(version != null ? version.getVersionName() : null)
                .added(added)
                .updated(updated)
                .build();
    }

    // 같은 질문의 FAQ가 여러 개면 ID → 내용이 같은 FAQ → 카테고리 순으로 고르고, 못 고르면 null(건너뜀)
    private Faq pickTarget(ImportRow row, List<Faq> candidates, Map<Integer, String> masterNames) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (row.id() != null) {
            for (Faq candidate : candidates) {
                if (candidate.getFaqId().equals(row.id())) return candidate;
            }
        }
        for (Faq candidate : candidates) {
            if (changedFields(row, candidate, masterNames).isEmpty()) return candidate;
        }
        if (row.category() != null) {
            String key = FaqService.categoryNameKey(row.category());
            List<Faq> sameCategory = candidates.stream()
                    .filter(c -> {
                        String name = displayCategory(c, masterNames);
                        return name != null && FaqService.categoryNameKey(name).equals(key);
                    })
                    .toList();
            if (sameCategory.size() == 1) return sameCategory.get(0);
        }
        return null;
    }

    // 파일 값과 다른 칸, 열이 없는 항목은 비교하지 않는다 (질문은 공백만 다를 수 있어 바꾸지 않는다)
    private List<String> changedFields(ImportRow row, Faq faq, Map<Integer, String> masterNames) {
        List<String> fields = new ArrayList<>();
        if (!row.answer().equals(faq.getAnswer())) fields.add("answer");
        if (row.hasCategory()) {
            String current = displayCategory(faq, masterNames);
            String currentKey = current != null ? FaqService.categoryNameKey(current) : null;
            String rowKey = row.category() != null ? FaqService.categoryNameKey(row.category()) : null;
            if (!Objects.equals(currentKey, rowKey)) fields.add("category");
        }
        if (row.keywords() != null && !row.keywords().equals(parseList(faq.getKeywords()))) fields.add("keywords");
        if (row.synonyms() != null && !row.synonyms().equals(parseList(faq.getSynonyms()))) fields.add("synonyms");
        return fields;
    }

    private Faq add(ImportRow row, User user, LinkedHashSet<String> newCategories) {
        FaqService.CategoryRef category = row.category() != null ? resolveCategory(row.category(), user, newCategories) : null;
        Faq faq = faqRepository.save(Faq.builder()
                .question(row.question())
                .answer(row.answer())
                .keywords(toJson(row.keywords() != null ? row.keywords() : List.of()))
                .synonyms(toJson(row.synonyms() != null ? row.synonyms() : List.of()))
                .qType(category != null ? category.qType() : null)
                .category(category != null ? category.name() : null)
                .createdBy(user)
                .build());
        faqEditHistoryRepository.save(FaqEditHistory.builder()
                .faq(faq)
                .editedBy(user)
                .editType(EditType.CREATE)
                .afterQuestion(faq.getQuestion())
                .afterAnswer(faq.getAnswer())
                .afterKeywords(parseList(faq.getKeywords()))
                .afterSynonyms(parseList(faq.getSynonyms()))
                .editReason(EDIT_REASON)
                .build());
        return faq;
    }

    private void update(ImportRow row, Faq faq, List<String> changed, User user, LinkedHashSet<String> newCategories) {
        String beforeAnswer = faq.getAnswer();
        List<String> beforeKeywords = parseList(faq.getKeywords());
        List<String> beforeSynonyms = parseList(faq.getSynonyms());

        Integer qType = faq.getQType();
        String category = faq.getCategory();
        if (changed.contains("category")) {
            FaqService.CategoryRef ref = row.category() != null ? resolveCategory(row.category(), user, newCategories) : null;
            qType = ref != null ? ref.qType() : null;
            category = ref != null ? ref.name() : null;
        }
        faq.restoreFrom(faq.getQuestion(), row.answer(),
                changed.contains("keywords") ? toJson(row.keywords()) : faq.getKeywords(),
                changed.contains("synonyms") ? toJson(row.synonyms()) : faq.getSynonyms(),
                qType, category);

        faqEditHistoryRepository.save(FaqEditHistory.builder()
                .faq(faq)
                .editedBy(user)
                .editType(EditType.MANUAL)
                .beforeQuestion(faq.getQuestion())
                .beforeAnswer(beforeAnswer)
                .beforeKeywords(beforeKeywords)
                .beforeSynonyms(beforeSynonyms)
                .afterQuestion(faq.getQuestion())
                .afterAnswer(faq.getAnswer())
                .afterKeywords(parseList(faq.getKeywords()))
                .afterSynonyms(parseList(faq.getSynonyms()))
                .editReason(EDIT_REASON)
                .build());
    }

    private FaqService.CategoryRef resolveCategory(String name, User user, LinkedHashSet<String> newCategories) {
        FaqService.CategoryRef ref = faqService.findOrCreateCategory(name, user);
        if (ref.created()) newCategories.add(ref.name());
        return ref;
    }

    // 화면에 보이는 카테고리 이름, 저장된 이름이 없으면 코드로 마스터에서 찾는다
    private String displayCategory(Faq faq, Map<Integer, String> masterNames) {
        if (faq.getCategory() != null || faq.getQType() == null) return faq.getCategory();
        return masterNames.get(faq.getQType());
    }

    // ===== 파일 읽기와 검사 =====

    private ParsedFile parse(AnalysisUploadReader.PipelineFile file) {
        Report report = new Report();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.content()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw report.single("CSV 파일을 UTF-8로 읽을 수 없습니다. UTF-8로 저장해 주세요.");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }

        List<CSVRecord> records;
        try (CSVParser parser = CSVParser.parse(text, CSVFormat.DEFAULT)) {
            records = parser.getRecords();
        } catch (IOException | UncheckedIOException e) {
            throw report.single("CSV 형식을 읽을 수 없습니다. 따옴표가 닫히지 않은 칸이 있는지 확인해 주세요.");
        }
        if (records.isEmpty()) {
            throw report.single("파일에 내용이 없습니다.");
        }

        RowNumbers numbers = new RowNumbers(text, file.rowNumbers());
        CSVRecord header = records.get(0);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.putIfAbsent(header.get(i).strip(), i);
        }
        for (String required : List.of("질문", "답변")) {
            if (!columns.containsKey(required)) {
                report.add(numbers.of(header, 0), required, "필수 열이 없습니다.");
            }
        }
        report.throwIfAny();

        boolean hasCategory = columns.containsKey("카테고리");
        boolean hasKeywords = columns.containsKey("키워드");
        boolean hasSynonyms = columns.containsKey("유사어");
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            CSVRecord record = records.get(i);
            if (record.stream().allMatch(String::isBlank)) continue;
            int row = numbers.of(record, i);

            String question = cell(record, columns.get("질문"));
            String answer = cell(record, columns.get("답변"));
            boolean valid = true;
            if (question.isBlank()) {
                report.add(row, "질문", "질문이 비어 있습니다.");
                valid = false;
            }
            if (answer.isBlank()) {
                report.add(row, "답변", "답변이 비어 있습니다.");
                valid = false;
            }
            String category = null;
            if (hasCategory && !cell(record, columns.get("카테고리")).isBlank()) {
                category = FaqService.normalizeCategoryName(cell(record, columns.get("카테고리")));
                if (category.length() > MAX_CATEGORY_LENGTH) {
                    report.add(row, "카테고리", "카테고리 이름은 100자 이하여야 합니다.");
                    valid = false;
                }
            }
            if (!valid) continue;

            rows.add(new ImportRow(row, parseId(cell(record, columns.get("ID"))), question.strip(), questionKey(question),
                    answer, hasCategory, category,
                    hasKeywords ? splitKeywords(cell(record, columns.get("키워드"))) : null,
                    hasSynonyms ? FaqService.normalizeSynonyms(split(cell(record, columns.get("유사어")))) : null));
        }
        if (rows.isEmpty() && report.total == 0) {
            throw report.single("데이터 행이 없습니다.");
        }

        // 파일 안에서 질문이 같으면 내용까지 같을 때만 한 건으로 합친다
        Map<String, ImportRow> firstByKey = new LinkedHashMap<>();
        Map<String, List<Integer>> mergedRows = new LinkedHashMap<>();
        List<ImportRow> unique = new ArrayList<>();
        for (ImportRow row : rows) {
            ImportRow first = firstByKey.putIfAbsent(row.key(), row);
            if (first == null) {
                unique.add(row);
            } else if (sameContent(first, row)) {
                mergedRows.computeIfAbsent(row.key(), k -> new ArrayList<>(List.of(first.row()))).add(row.row());
            } else {
                report.add(row.row(), "질문", first.row() + "행과 질문이 같은데 내용이 다릅니다.");
            }
        }
        report.throwIfAny();

        List<FaqImportResponse.MergedDuplicate> merged = mergedRows.entrySet().stream()
                .map(e -> FaqImportResponse.MergedDuplicate.builder()
                        .standardQuestion(firstByKey.get(e.getKey()).question())
                        .rows(e.getValue())
                        .build())
                .toList();
        return new ParsedFile(unique, merged);
    }

    private static boolean sameContent(ImportRow a, ImportRow b) {
        return a.answer().equals(b.answer())
                && Objects.equals(a.category() != null ? FaqService.categoryNameKey(a.category()) : null,
                                  b.category() != null ? FaqService.categoryNameKey(b.category()) : null)
                && Objects.equals(a.keywords(), b.keywords())
                && Objects.equals(a.synonyms(), b.synonyms());
    }

    // 같은 FAQ인지 판단하는 질문: 앞뒤 공백을 지우고 연속 공백을 하나로 줄인다
    static String questionKey(String question) {
        return question.strip().replaceAll("(?U)\\s+", " ");
    }

    private static String cell(CSVRecord record, Integer column) {
        if (column == null || column >= record.size()) return "";
        return record.get(column);
    }

    // 쉼표로 나눈 키워드, 앞뒤 공백·빈 값·완전히 같은 중복을 뺀다 (순서 유지)
    private static List<String> splitKeywords(String value) {
        return new ArrayList<>(new LinkedHashSet<>(split(value)));
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ID 열은 같은 질문의 FAQ가 여러 개일 때만 쓴다, 숫자가 아니면 무시한다
    private static Long parseId(String value) {
        try {
            return value.isBlank() ? null : Long.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("키워드 직렬화 실패", e);
        }
    }

    private List<String> parseList(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("키워드 파싱 실패: {}", json);
            return List.of();
        }
    }

    // 오류를 행 번호와 함께 모은다. 앞의 일부만 응답에 담고 전체 건수를 센다 (분석 업로드 검사와 같은 형식)
    private static class Report {
        private final List<UploadErrorResponse.ErrorItem> errors = new ArrayList<>();
        private int total;

        void add(Integer row, String column, String reason) {
            total++;
            if (errors.size() < MAX_REPORTED_ERRORS) {
                errors.add(UploadErrorResponse.ErrorItem.builder().row(row).column(column).reason(reason).build());
            }
        }

        UploadValidationException single(String reason) {
            add(null, null, reason);
            return exception();
        }

        void throwIfAny() {
            if (total > 0) throw exception();
        }

        private UploadValidationException exception() {
            return new UploadValidationException(UploadErrorResponse.builder()
                    .totalErrors(total)
                    .errors(List.copyOf(errors))
                    .build());
        }
    }

    // 사용자가 파일에서 찾을 수 있는 행 번호
    // 엑셀에서 변환했으면 원래 엑셀 행 번호, CSV면 그 행이 시작하는 파일의 줄 번호
    private static class RowNumbers {
        private final List<Integer> excelRows;
        private final List<Integer> lineStarts = new ArrayList<>();

        RowNumbers(String text, List<Integer> excelRows) {
            this.excelRows = excelRows;
            lineStarts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') lineStarts.add(i + 1);
            }
        }

        int of(CSVRecord record, int index) {
            if (excelRows != null && index < excelRows.size()) {
                return excelRows.get(index);
            }
            int position = (int) record.getCharacterPosition();
            int found = Collections.binarySearch(lineStarts, position, Comparator.naturalOrder());
            return (found >= 0 ? found : -found - 2) + 1;
        }
    }
}
