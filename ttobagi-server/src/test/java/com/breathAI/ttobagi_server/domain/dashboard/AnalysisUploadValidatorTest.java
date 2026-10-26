package com.breathAI.ttobagi_server.domain.dashboard;

import com.breathAI.ttobagi_server.domain.dashboard.dto.UploadErrorResponse;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisUploadReader;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisUploadValidator;
import com.breathAI.ttobagi_server.global.exception.UploadValidationException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 업로드 파일 내용 검사, 규칙은 AI 입력 검사(pipeline_api.py, dashboard_metrics.py)와 같다
class AnalysisUploadValidatorTest {

    private final AnalysisUploadReader reader = new AnalysisUploadReader();
    private final AnalysisUploadValidator validator = new AnalysisUploadValidator();

    @Test
    @DisplayName("AI가 받는 형식은 통과한다 (BOM, 여러 ISO 날짜, logType 생략·빈칸, 번호 공백·부호)")
    void acceptsWhatAiAccepts() {
        String csv = "﻿번호,질문,날짜,logType\n"
                + "1,질문,2026-08-27,\n"
                + " +2 ,질문,2026-08-27 12:04:00,CORRECT\n"
                + "3,,2026-08-27T12:04,LOW_QUALITY\n"
                + "4,질문,20260827,UNANSWER\n"
                + "5,질문,2026-08-27T12:04:00.5+09:00,\n";
        assertThatCode(() -> validateCsv(csv)).doesNotThrowAnyException();
        assertThatCode(() -> validateCsv("번호,질문,날짜\n1,질문,2026-08-27\n")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("필수 칸이 없으면 머리글 행에 칸마다 알려 준다")
    void missingColumns() {
        UploadErrorResponse report = errors("질문,답변\n안녕,반가워\n");
        assertThat(report.getTotalErrors()).isEqualTo(2);
        assertThat(report.getErrors()).extracting(UploadErrorResponse.ErrorItem::getColumn).containsExactly("번호", "날짜");
        assertThat(report.getErrors()).extracting(UploadErrorResponse.ErrorItem::getRow).containsOnly(1);
    }

    @Test
    @DisplayName("번호·날짜·logType 오류를 행 번호와 함께 모두 알려 준다")
    void rowErrors() {
        String csv = "번호,질문,날짜,logType\n"
                + "1,질문,2026-08-27,\n"
                + "1,질문,2026-08-27,\n"
                + "가,질문,2026-08-27,\n"
                + "3000000000,질문,2026-08-27,\n"
                + ",질문,2026-08-27,\n"
                + "6,질문,8/27/26,\n"
                + "7,질문,2026-02-30,\n"
                + "8,질문,,\n"
                + "9,질문,2026-08-27,OTHER\n"
                + "10,질문,2026-08-27,  \n";
        UploadErrorResponse report = errors(csv);
        assertThat(report.getTotalErrors()).isEqualTo(9);
        assertThat(report.getErrors()).extracting(UploadErrorResponse.ErrorItem::getRow)
                .containsExactly(3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(report.getErrors().get(0).getReason()).contains("2행과 중복");
        assertThat(report.getErrors()).extracting(UploadErrorResponse.ErrorItem::getColumn)
                .containsExactly("번호", "번호", "번호", "번호", "날짜", "날짜", "날짜", "logType", "logType");
    }

    @Test
    @DisplayName("CSV 행 번호는 빈 줄과 여러 줄짜리 칸이 있어도 파일의 줄 번호를 가리킨다")
    void csvLineNumbers() {
        String csv = "번호,질문,날짜\n\n1,\"두 줄\n질문\",2026-08-27\n2,질문,날짜아님\n";
        UploadErrorResponse report = errors(csv);
        assertThat(report.getErrors()).extracting(UploadErrorResponse.ErrorItem::getRow).containsExactly(5);
    }

    @Test
    @DisplayName("엑셀은 빈 행을 건너뛰어도 원래 엑셀 행 번호로 알려 준다")
    void excelRowNumbers() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("번호");
            header.createCell(1).setCellValue("질문");
            header.createCell(2).setCellValue("날짜");
            var ok = sheet.createRow(1);
            ok.createCell(0).setCellValue(1);
            ok.createCell(1).setCellValue("질문");
            ok.createCell(2).setCellValue("2026-08-27");
            sheet.createRow(2);
            var bad = sheet.createRow(5);
            bad.createCell(0).setCellValue(2);
            bad.createCell(1).setCellValue("질문");
            bad.createCell(2).setCellValue("날짜아님");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var file = reader.read(new MockMultipartFile("file", "logs.xlsx", null, bytes));
        UploadValidationException e = assertThrows(UploadValidationException.class, () -> validator.validate(file));
        assertThat(e.getReport().getErrors()).extracting(UploadErrorResponse.ErrorItem::getRow).containsExactly(6);
    }

    @Test
    @DisplayName("오류는 앞 100건만 담고 전체 건수를 함께 준다")
    void caps() {
        StringBuilder csv = new StringBuilder("번호,질문,날짜\n");
        for (int i = 0; i < 150; i++) {
            csv.append(i).append(",질문,틀린날짜\n");
        }
        UploadErrorResponse report = errors(csv.toString());
        assertThat(report.getTotalErrors()).isEqualTo(150);
        assertThat(report.getErrors()).hasSize(100);
    }

    @Test
    @DisplayName("UTF-8이 아니거나 데이터 행이 없으면 파일 전체 오류로 알려 준다")
    void fileLevelErrors() {
        UploadErrorResponse notUtf8 = errors(new byte[]{(byte) 0xB9, (byte) 0xF8, (byte) 0xC8, (byte) 0xA3, '\n'});
        assertThat(notUtf8.getErrors().get(0).getRow()).isNull();
        assertThat(notUtf8.getErrors().get(0).getReason()).contains("UTF-8");

        UploadErrorResponse headerOnly = errors("번호,질문,날짜\n");
        assertThat(headerOnly.getErrors().get(0).getReason()).contains("데이터 행이 없습니다");
    }

    private void validateCsv(String csv) {
        validator.validate(new AnalysisUploadReader.PipelineFile("logs.csv", csv.getBytes(StandardCharsets.UTF_8), null));
    }

    private UploadErrorResponse errors(String csv) {
        return errors(csv.getBytes(StandardCharsets.UTF_8));
    }

    private UploadErrorResponse errors(byte[] content) {
        UploadValidationException e = assertThrows(UploadValidationException.class,
                () -> validator.validate(new AnalysisUploadReader.PipelineFile("logs.csv", content, null)));
        return e.getReport();
    }
}
