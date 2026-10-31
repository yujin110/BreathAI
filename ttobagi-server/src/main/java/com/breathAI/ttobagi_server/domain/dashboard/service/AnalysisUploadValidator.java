package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.dashboard.dto.UploadErrorResponse;
import com.breathAI.ttobagi_server.global.exception.UploadValidationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// AI로 보내기 전에 업로드 파일 내용을 검사해 행별 오류를 알려 준다
// 규칙은 AI 입력 검사(services/pipeline_api.py, dashboard_metrics.py)와 맞춘다
// 날짜는 AI(Python fromisoformat)가 받는 ISO 형식보다 좁게 막지 않는다. 더 넓게 통과한 값은 AI가 다시 거절한다
@Component
public class AnalysisUploadValidator {

    static final int MAX_REPORTED_ERRORS = 100;

    private static final List<String> REQUIRED_COLUMNS = List.of("번호", "질문", "날짜");
    private static final Set<String> LOG_TYPES = Set.of("CORRECT", "LOW_QUALITY", "UNANSWER");
    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    // Python int()처럼 앞뒤 공백, 부호, 숫자 사이 밑줄을 허용한다
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+(_\\d+)*");
    private static final Pattern PERCENT = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    // 2026-08-27, 20260827, 2026-08-27 12:04:00, 2026-08-27T12:04:00.5+09:00 등
    private static final Pattern ISO_DATE = Pattern.compile(
            "(\\d{4})-?(\\d{2})-?(\\d{2})"
            + "(?:[T ](\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,]\\d+)?)?)?)?"
            + "(?:Z|[+-]\\d{2}(?::?\\d{2}(?::?\\d{2}(?:[.,]\\d+)?)?)?)?");

    public void validate(AnalysisUploadReader.PipelineFile file) {
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
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(text))) {
            records = parser.getRecords();
        } catch (Exception e) {
            throw report.single("CSV 형식을 읽을 수 없습니다. 따옴표가 닫히지 않은 칸이 있는지 확인해 주세요.");
        }
        if (records.isEmpty()) {
            throw report.single("파일에 내용이 없습니다.");
        }

        LineNumbers lines = new LineNumbers(text, file.rowNumbers());
        // AI는 칸 이름의 줄바꿈을 지우고 읽으며, 같은 이름이 여러 번 나오면 마지막 칸을 쓴다
        Map<String, Integer> columns = new HashMap<>();
        CSVRecord header = records.get(0);
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).replace("\n", ""), i);
        }
        for (String required : REQUIRED_COLUMNS) {
            if (!columns.containsKey(required)) {
                report.add(lines.of(0, header), required, "필수 칸이 없습니다. 첫 줄에 '" + required + "' 칸이 있어야 합니다.");
            }
        }
        report.throwIfAny();
        if (records.size() == 1) {
            throw report.single("데이터 행이 없습니다.");
        }

        Integer logTypeColumn = columns.get("logType");
        Integer percentColumn = columns.get("keywordMatchPercent");
        Map<BigInteger, Integer> seenNumbers = new HashMap<>();
        for (int i = 1; i < records.size(); i++) {
            CSVRecord record = records.get(i);
            int row = lines.of(i, record);

            String number = cell(record, columns.get("번호"));
            if (number == null || number.isBlank()) {
                report.add(row, "번호", "번호가 비어 있습니다.");
            } else if (!INTEGER.matcher(number.strip()).matches()) {
                report.add(row, "번호", "번호는 정수여야 합니다: " + number.strip());
            } else {
                BigInteger value = new BigInteger(number.strip().replace("_", ""));
                if (value.compareTo(INT_MIN) < 0 || value.compareTo(INT_MAX) > 0) {
                    report.add(row, "번호", "번호가 허용 범위를 벗어났습니다: " + number.strip());
                } else {
                    Integer firstRow = seenNumbers.putIfAbsent(value, row);
                    if (firstRow != null) {
                        report.add(row, "번호", "번호 " + value + "이(가) " + firstRow + "행과 중복됩니다.");
                    }
                }
            }

            String date = cell(record, columns.get("날짜"));
            if (date == null || date.isBlank()) {
                report.add(row, "날짜", "날짜가 비어 있습니다.");
            } else if (!isIsoDate(date.strip())) {
                report.add(row, "날짜", "날짜 형식이 올바르지 않습니다(예: 2026-08-27 또는 2026-08-27 12:04:00): " + date.strip());
            }

            // 분류가 없거나 비어 있으면 아래 매칭률 검사를 따른다. 공백만 있는 분류는 오류다.
            String logType = logTypeColumn != null ? cell(record, logTypeColumn) : null;
            if (logType != null && !logType.isEmpty() && !LOG_TYPES.contains(logType.strip())) {
                report.add(row, "logType", "logType은 CORRECT, LOW_QUALITY, UNANSWER 중 하나여야 합니다: " + logType.strip());
            }
            // AI와 같은 우선순위: 명시적 logType → 백분율 → 원본 점수 → 기존 미답변 전용 입력.
            if (logType == null || logType.isEmpty()) {
                if (percentColumn != null) {
                    String percent = cell(record, percentColumn);
                    if (!isPercent(percent)) {
                        report.add(row, "keywordMatchPercent", "키워드 매칭률은 0~100의 백분율이어야 합니다(예: 30 또는 30%).");
                    }
                } else if (columns.containsKey("ai_percent")) {
                    String raw = cell(record, columns.get("ai_percent"));
                    if (raw == null || !raw.strip().matches("[0-9]+")
                            || new BigInteger(raw.strip()).compareTo(BigInteger.valueOf(10000)) > 0) {
                        report.add(row, "ai_percent", "원본 매칭 점수는 0~10000의 정수여야 합니다(9999 = 99.99%).");
                    }
                }
            }
        }
        report.throwIfAny();
    }

    // 칸이 모자란 줄은 그 칸이 없는 것으로 본다
    private static String cell(CSVRecord record, Integer column) {
        return column != null && column < record.size() ? record.get(column) : null;
    }

    private static boolean isIsoDate(String value) {
        Matcher m = ISO_DATE.matcher(value);
        if (!m.matches()) return false;
        try {
            LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return false;
        }
        return inRange(m.group(4), 23) && inRange(m.group(5), 59) && inRange(m.group(6), 59);
    }

    private static boolean isPercent(String value) {
        if (value == null) return false;
        String text = value.strip();
        if (text.endsWith("%")) text = text.substring(0, text.length() - 1).strip();
        if (!PERCENT.matcher(text).matches()) return false;
        BigDecimal number = new BigDecimal(text);
        return number.signum() >= 0 && number.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    private static boolean inRange(String value, int max) {
        return value == null || Integer.parseInt(value) <= max;
    }

    // 오류를 행 번호와 함께 모은다. 앞의 일부만 응답에 담고 전체 건수를 센다
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
    // 엑셀에서 변환했으면 원래 엑셀 행 번호, CSV면 그 줄이 시작하는 파일의 줄 번호 (빈 줄을 건너뛰어도 맞게)
    private static class LineNumbers {
        private final List<Integer> excelRows;
        private final int[] lineStarts;

        LineNumbers(String text, List<Integer> excelRows) {
            this.excelRows = excelRows;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') starts.add(i + 1);
            }
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        int of(int recordIndex, CSVRecord record) {
            if (excelRows != null && recordIndex < excelRows.size()) {
                return excelRows.get(recordIndex);
            }
            int position = Arrays.binarySearch(lineStarts, (int) record.getCharacterPosition());
            return (position >= 0 ? position : -position - 2) + 1;
        }
    }
}
