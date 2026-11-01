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
