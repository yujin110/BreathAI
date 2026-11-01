package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.breathAI.ttobagi_server.global.exception.UploadValidationException;
import com.breathAI.ttobagi_server.domain.dashboard.dto.UploadErrorResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.StringWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Materializes the upload before asynchronous dispatch; XLSX uses its first sheet. */
@Component
public class AnalysisUploadReader {
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 정규화한 경우 CSV 각 레코드(머리글 포함)의 원본 파일 행 번호. 원문을 유지한 CSV면 null.
    public record PipelineFile(String filename, byte[] content, List<Integer> rowNumbers) {}

    public PipelineFile read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_UPLOAD_FILE);
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.toLowerCase(Locale.ROOT).endsWith(".csv")
                || filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))) {
            throw new CustomException(ErrorCode.UNSUPPORTED_UPLOAD_FORMAT);
        }
        try {
            if (filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                return normalize(new PipelineFile(filename, decodeCsv(file.getBytes()).getBytes(StandardCharsets.UTF_8), null));
            }
            try (var input = file.getInputStream(); var workbook = new XSSFWorkbook(input)) {
                if (workbook.getNumberOfSheets() == 0) {
                    throw new CustomException(ErrorCode.INVALID_UPLOAD_FILE);
                }
                var sheet = workbook.getSheetAt(0);
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null || header.getLastCellNum() <= 0) {
                    throw new CustomException(ErrorCode.INVALID_UPLOAD_FILE);
                }
                int columns = header.getLastCellNum();
                var formatter = new DataFormatter(Locale.ROOT);
                var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                var output = new StringWriter();
                var rowNumbers = new ArrayList<Integer>();
                try (var csv = new CSVPrinter(output, CSVFormat.DEFAULT)) {
                    for (Row row : sheet) {
                        var values = new ArrayList<String>();
                        for (int column = 0; column < Math.max(columns, row.getLastCellNum()); column++) {
                            values.add(cellText(row.getCell(column), formatter, evaluator));
                        }
                        if (values.stream().anyMatch(value -> !value.isBlank())) {
                            csv.printRecord(values);
                            rowNumbers.add(row.getRowNum() + 1);
                        }
                    }
                }
                if (output.toString().isBlank()) {
                    throw new CustomException(ErrorCode.INVALID_UPLOAD_FILE);
                }
                return normalize(new PipelineFile(filename.substring(0, filename.length() - 5) + ".csv",
                        output.toString().getBytes(StandardCharsets.UTF_8), rowNumbers));
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_UPLOAD_FILE);
        }
    }

    private static String decodeCsv(byte[] bytes) throws CharacterCodingException {
        try {
            return decode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return decode(bytes, Charset.forName("MS949"));
        }
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static PipelineFile normalize(PipelineFile file) throws Exception {
        String text = new String(file.content(), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<CSVRecord> records;
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(text))) {
            records = parser.getRecords();
        }
        if (records.isEmpty()) return file; // 기존 검증기가 파일 오류를 설명한다.
        List<String> header = new ArrayList<>();
        for (String name : records.get(0)) header.add(name.replace("\n", ""));
        if (header.containsAll(Set.of("번호", "질문", "날짜"))) return file;
        if (!header.containsAll(Set.of("seq_num", "kind", "msg_m", "sysdate"))) {
            if (header.containsAll(Set.of("qa_date", "answer_cnt", "unanswer_cnt"))) {
                throw inputError(null, null, "날짜별 집계 파일은 개별 질문이 없어 FAQ 분석에 사용할 수 없습니다. 대화 로그 파일을 업로드해 주세요.");
            }
            return file; // 필수 열 누락은 기존 검증기가 보고한다.
        }
        if (header.stream().anyMatch(Set.of("번호", "질문", "날짜")::contains)) {
            throw inputError(null, null, "원본 대화 로그와 분석용 열 이름이 혼합되어 있습니다.");
        }
        if (header.stream().noneMatch(Set.of("logType", "keywordMatchPercent", "ai_percent")::contains)) {
            throw inputError(null, null, "대화 로그에는 logType 또는 키워드 매칭률이 있어야 합니다.");
        }
        Map<String, String> aliases = Map.of("seq_num", "번호", "msg_m", "질문", "sysdate", "날짜");
        List<String> normalizedHeader = header.stream().map(name -> aliases.getOrDefault(name, name)).toList();
        int kindIndex = header.lastIndexOf("kind");
        var lineStarts = new ArrayList<Integer>();
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lineStarts.add(i + 1);
        int[] starts = lineStarts.stream().mapToInt(Integer::intValue).toArray();
        var rowNumbers = new ArrayList<Integer>();
        var output = new StringWriter();
        try (var csv = new CSVPrinter(output, CSVFormat.DEFAULT)) {
            csv.printRecord(normalizedHeader);
            rowNumbers.add(originalRow(file, text, starts, records.get(0), 0));
            for (int i = 1; i < records.size(); i++) {
                CSVRecord record = records.get(i);
                int row = originalRow(file, text, starts, record, i);
                String kind = kindIndex < record.size() ? record.get(kindIndex).strip() : "";
                if (kind.equals("0")) continue;
                if (!kind.equals("1")) throw inputError(row, "kind", "kind는 0(질문) 또는 1(응답)이어야 합니다.");
                csv.printRecord(record);
                rowNumbers.add(row);
            }
        }
        return new PipelineFile(file.filename(), output.toString().getBytes(StandardCharsets.UTF_8), rowNumbers);
    }

    private static int originalRow(PipelineFile file, String text, int[] starts, CSVRecord record, int index) {
        if (file.rowNumbers() != null && index < file.rowNumbers().size()) return file.rowNumbers().get(index);
        // Commons CSV의 위치는 건너뛴 빈 줄 앞을 가리킬 수 있다.
        int offset = (int) record.getCharacterPosition();
        while (offset < text.length() && (text.charAt(offset) == '\r' || text.charAt(offset) == '\n')) offset++;
        int position = Arrays.binarySearch(starts, offset);
        return (position >= 0 ? position : -position - 2) + 1;
    }

    private static UploadValidationException inputError(Integer row, String column, String reason) {
        return new UploadValidationException(UploadErrorResponse.builder().totalErrors(1)
                .errors(List.of(UploadErrorResponse.ErrorItem.builder().row(row).column(column).reason(reason).build()))
                .build());
    }

    // 날짜 칸은 화면 서식(예: 8/27/26 12:04) 대신 ISO 형식으로 쓴다. AI는 ISO 날짜만 받는다
    // 시각이 자정이면 날짜만 쓰고, 나머지 칸은 화면에 보이는 값 그대로 쓴다
    private static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell != null && isNumeric(cell, evaluator) && DateUtil.isCellDateFormatted(cell)) {
            // 엑셀 날짜는 실수로 저장돼 1초 미만 오차가 생길 수 있어 가장 가까운 초로 맞춘다
            LocalDateTime value = cell.getLocalDateTimeCellValue().plusNanos(500_000_000).truncatedTo(ChronoUnit.SECONDS);
            return value.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? value.toLocalDate().toString()
                    : value.format(ISO_DATE_TIME);
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private static boolean isNumeric(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType() == CellType.FORMULA ? evaluator.evaluateFormulaCell(cell) : cell.getCellType();
        return type == CellType.NUMERIC;
    }
}
