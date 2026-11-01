package com.breathAI.ttobagi_server.domain.dashboard;

import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisUploadReader;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisUploadValidator;
import com.breathAI.ttobagi_server.global.exception.UploadValidationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisUploadCompatibilityTest {
    private final AnalysisUploadReader reader = new AnalysisUploadReader();
    private final AnalysisUploadValidator validator = new AnalysisUploadValidator();
    private static final String RAW = "seq_num,kind,msg_m,msg_m_txt,sysdate,ai_percent,logType\n"
            + "1,0,원본 질문,재생성 질문,2026-03-01 00:00:00,0,\n"
            + "2,1,원본 질문,재생성 질문,2026-03-01 00:00:00,잘못된점수,CORRECT\n"
            + "3,1,환승 문의,재생성 질문,2026-03-02 00:00:00,2500,\n"
            + "4,1,시간 문의,재생성 질문,2026-03-03 00:00:00,9999,\n";

    @Test
    void originalUtf8AndCp949AreAcceptedWithoutManualConversion() throws Exception {
        for (Charset charset : new Charset[]{StandardCharsets.UTF_8, Charset.forName("MS949")}) {
            var file = reader.read(new MockMultipartFile("file", "chatting_log.csv", null, RAW.getBytes(charset)));
            assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
            try (var parsed = CSVFormat.DEFAULT.parse(new StringReader(new String(file.content(), StandardCharsets.UTF_8)))) {
                var records = parsed.getRecords();
                assertThat(records).hasSize(4);
                assertThat(records.get(0).get(0)).isEqualTo("번호");
                assertThat(records.get(0).get(2)).isEqualTo("질문");
                assertThat(records.get(0).get(4)).isEqualTo("날짜");
                assertThat(records.get(1).get(0)).isEqualTo("2");
                assertThat(records.get(1).get(2)).isEqualTo("원본 질문");
                assertThat(records.get(1).get(6)).isEqualTo("CORRECT");
                assertThat(records.get(3).get(5)).isEqualTo("9999");
            }
            assertThat(file.rowNumbers()).containsExactly(1, 3, 4, 5);
        }
    }

    @Test
    void canonicalCsvIsPreservedIncludingLabelsAndKind() {
        String csv = "번호,질문,날짜,kind,logType\n1,문의,2026-03-01,0,CORRECT\n";
        var file = reader.read(new MockMultipartFile("file", "analysis.csv", null, csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(file.content()).isEqualTo(csv.getBytes(StandardCharsets.UTF_8));
        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void filteredCsvErrorsStillReferToOriginalPhysicalLines() {
        String csv = "seq_num,kind,msg_m,sysdate,ai_percent\n"
                + "1,0,\"두 줄\n질문\",2026-03-01,0\n\n"
                + "2,1,문의,잘못된날짜,9999\n";
        var file = reader.read(new MockMultipartFile("file", "raw.csv", null, csv.getBytes(StandardCharsets.UTF_8)));
        var error = assertThrows(UploadValidationException.class, () -> validator.validate(file));
        assertThat(error.getReport().getErrors()).extracting(e -> e.getRow()).containsExactly(5);
    }

    @Test
    void originalXlsxUsesTheSameNormalizationAndKeepsExcelRowNumbers() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            String[] fields = {"seq_num", "kind", "msg_m", "sysdate", "ai_percent"};
            var header = sheet.createRow(0);
            for (int i = 0; i < fields.length; i++) header.createCell(i).setCellValue(fields[i]);
            var question = sheet.createRow(1);
            for (int i = 0; i < fields.length; i++) question.createCell(i).setCellValue(new String[]{"1", "0", "문의", "2026-03-01", "0"}[i]);
            var response = sheet.createRow(5);
            for (int i = 0; i < fields.length; i++) response.createCell(i).setCellValue(new String[]{"2", "1", "문의", "잘못된날짜", "9999"}[i]);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var file = reader.read(new MockMultipartFile("file", "raw.xlsx", null, bytes));
        var error = assertThrows(UploadValidationException.class, () -> validator.validate(file));
        assertThat(error.getReport().getErrors()).extracting(e -> e.getRow()).containsExactly(6);
    }

    // 공사 미답변 엑셀과 같은 모양: 답변확률은 숫자에 백분율 서식(0%, 0.00%), 일부는 "0 %" 글자
    private static byte[] answerProbabilityXlsx(Object[][] rows) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var whole = workbook.createCellStyle();
            whole.setDataFormat(workbook.createDataFormat().getFormat("0%"));
            var decimal = workbook.createCellStyle();
            decimal.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
            String[] header = {"번호", "도메인", "질문", "답변확률", "상태", "날짜"};
            var head = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) head.createCell(i).setCellValue(header[i]);
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r + 1);
                row.createCell(0).setCellValue((Integer) rows[r][0]);
                row.createCell(1).setCellValue("시민");
                row.createCell(2).setCellValue("질문 " + rows[r][0]);
                var probability = row.createCell(3);
                if (rows[r][1] instanceof Double value) {
                    probability.setCellValue(value);
                    probability.setCellStyle(value == 0 ? whole : decimal);
                } else if (rows[r][1] != null) {
                    probability.setCellValue((String) rows[r][1]);
                }
                row.createCell(4).setCellValue("학습대기");
                row.createCell(5).setCellValue("2026-08-27");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static java.util.List<org.apache.commons.csv.CSVRecord> records(AnalysisUploadReader.PipelineFile file) throws Exception {
        return CSVFormat.DEFAULT.parse(new StringReader(new String(file.content(), StandardCharsets.UTF_8))).getRecords();
    }

    @Test
    void answerProbabilityXlsxIsSentAsKeywordMatchPercent() throws Exception {
        byte[] xlsx = answerProbabilityXlsx(new Object[][]{{1, 0.0}, {2, 0.2402}, {3, 0.25}, {4, 0.7499}, {5, 0.75}, {6, "0 %"}});
        var file = reader.read(new MockMultipartFile("file", "unanswered.xlsx", null, xlsx));
        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
        var recs = records(file);
        assertThat(recs.get(0).toList()).containsExactly("번호", "도메인", "질문", "keywordMatchPercent", "상태", "날짜");
        assertThat(recs.stream().skip(1).map(r -> r.get(3)).toList())
                .containsExactly("0%", "24.02%", "25.00%", "74.99%", "75.00%", "0 %");
        assertThat(recs.get(2).get(2)).isEqualTo("질문 2");
        assertThat(file.rowNumbers()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(file.filename()).isEqualTo("unanswered.csv");
    }

    @Test
    void answerProbabilityCsvKeepsOriginalLineNumbersInErrors() {
        String csv = "번호,질문,날짜,답변확률\n"
                + "1,\"두 줄\n질문\",2026-08-27,24.02%\n\n"
                + "2,문의,2026-08-27,\n"
                + "3,문의,2026-08-27,150%\n";
        var file = reader.read(new MockMultipartFile("file", "unanswered.csv", null, csv.getBytes(StandardCharsets.UTF_8)));
        var error = assertThrows(UploadValidationException.class, () -> validator.validate(file));
        assertThat(error.getReport().getErrors()).extracting(e -> e.getRow()).containsExactly(5, 6);
        assertThat(error.getReport().getErrors()).extracting(e -> e.getColumn()).containsOnly("답변확률");
    }

    @Test
    void answerProbabilityErrorUsesExcelRowAndOriginalColumnName() throws Exception {
        byte[] xlsx = answerProbabilityXlsx(new Object[][]{{1, 0.1}, {2, null}, {3, "모름"}});
        var file = reader.read(new MockMultipartFile("file", "unanswered.xlsx", null, xlsx));
        var error = assertThrows(UploadValidationException.class, () -> validator.validate(file));
        assertThat(error.getReport().getErrors()).extracting(e -> e.getRow()).containsExactly(3, 4);
        assertThat(error.getReport().getErrors()).extracting(e -> e.getColumn()).containsOnly("답변확률");
    }

    @Test
    void answerProbabilityIsIgnoredWhenAnotherScoreOrLogTypeDecides() throws Exception {
        // 다른 매칭률 칸이 있으면 답변확률은 쓰지 않고 파일을 그대로 보낸다
        for (String csv : new String[]{
                "번호,질문,날짜,답변확률,keywordMatchPercent\n1,문의,2026-08-27,,80\n",
                "번호,질문,날짜,답변확률,ai_percent\n1,문의,2026-08-27,,8000\n"}) {
            var file = reader.read(new MockMultipartFile("file", "logs.csv", null, csv.getBytes(StandardCharsets.UTF_8)));
            assertThat(file.content()).isEqualTo(csv.getBytes(StandardCharsets.UTF_8));
            assertThat(file.renamedColumns()).isEmpty();
            assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
        }
        // logType이 있는 행은 답변확률이 비어 있어도 된다
        var labelled = reader.read(new MockMultipartFile("file", "logs.csv", null,
                "번호,질문,날짜,logType,답변확률\n1,문의,2026-08-27,CORRECT,\n2,문의,2026-08-27,,30%\n".getBytes(StandardCharsets.UTF_8)));
        assertThatCode(() -> validator.validate(labelled)).doesNotThrowAnyException();
        assertThat(records(labelled).get(0).toList()).containsExactly("번호", "질문", "날짜", "logType", "keywordMatchPercent");
    }

    @Test
    void canonicalCp949CsvIsReadAsUtf8() throws Exception {
        String csv = "번호,질문,날짜,logType\n1,\"막차, 첫차 시간\",2026-03-01,CORRECT\n";
        var file = reader.read(new MockMultipartFile("file", "analysis.csv", null, csv.getBytes(Charset.forName("MS949"))));
        assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo(csv);
        assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
    }

    @Test
    void unclosedQuoteStillReportsTheCsvFormatError() {
        for (String csv : new String[]{"번호,질문,날짜\n1,\"막차,2026-03-01\n",
                "seq_num,kind,msg_m,sysdate,ai_percent\n1,1,\"막차,2026-03-01,9999\n"}) {
            var file = reader.read(new MockMultipartFile("file", "quote.csv", null, csv.getBytes(StandardCharsets.UTF_8)));
            var error = assertThrows(UploadValidationException.class, () -> validator.validate(file));
            assertThat(error.getReport().getErrors().get(0).getReason()).startsWith("CSV 형식을 읽을 수 없습니다.");
        }
    }

    @Test
    void unreadableEncodingIsExplained() {
        var error = assertThrows(UploadValidationException.class, () -> reader.read(new MockMultipartFile(
                "file", "bin.csv", null, new byte[]{'a', '\n', (byte) 0x81, ' ', '\n'})));
        assertThat(error.getReport().getErrors().get(0).getReason()).contains("UTF-8 또는 CP949");
    }

    @Test
    void invalidKindAndDailyAggregatesAreExplainedBeforeAnalysisStarts() {
        var invalidKind = assertThrows(UploadValidationException.class, () -> reader.read(
                new MockMultipartFile("file", "raw.csv", null, RAW.replace("2,1,", "2,99,").getBytes(StandardCharsets.UTF_8))));
        assertThat(invalidKind.getReport().getErrors().get(0).getColumn()).isEqualTo("kind");
        var daily = assertThrows(UploadValidationException.class, () -> reader.read(new MockMultipartFile(
                "file", "chatting_log_day.csv", null, "qa_date,answer_cnt,unanswer_cnt\n2026-03-01,10,2\n".getBytes(StandardCharsets.UTF_8))));
        assertThat(daily.getReport().getErrors().get(0).getReason()).contains("개별 질문");
    }
}
