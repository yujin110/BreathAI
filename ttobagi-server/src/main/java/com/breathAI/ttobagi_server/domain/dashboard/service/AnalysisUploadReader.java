package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Materializes the upload before asynchronous dispatch; XLSX uses its first sheet. */
@Component
public class AnalysisUploadReader {
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // rowNumbers는 엑셀에서 변환한 경우 CSV 각 줄(머리글 포함)의 원래 엑셀 행 번호, CSV를 그대로 쓰면 null
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
                // Preserve existing CSV encoding and contents for the AI CSV reader.
                return new PipelineFile(filename, file.getBytes(), null);
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
                return new PipelineFile(filename.substring(0, filename.length() - 5) + ".csv",
                        output.toString().getBytes(StandardCharsets.UTF_8), rowNumbers);
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_UPLOAD_FILE);
        }
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
