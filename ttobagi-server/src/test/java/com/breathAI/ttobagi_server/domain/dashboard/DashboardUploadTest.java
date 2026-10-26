package com.breathAI.ttobagi_server.domain.dashboard;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.dashboard.entity.UploadFile;
import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisHistoryRepository;
import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisJobRepository;
import com.breathAI.ttobagi_server.domain.dashboard.repository.UploadFileRepository;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisUploadReader;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisUploadValidator;
import com.breathAI.ttobagi_server.domain.dashboard.service.DashboardService;
import com.breathAI.ttobagi_server.domain.dashboard.service.UploadArchiveService;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardUploadTest {
    @Mock UserRepository users;
    @Mock UploadFileRepository uploads;
    @Mock AnalysisJobRepository jobs;
    @Mock WebClientUtil client;
    @Mock UploadArchiveService archive;
    @Mock AnalysisHistoryRepository histories;
    @Spy AnalysisUploadReader reader = new AnalysisUploadReader();
    @Spy AnalysisUploadValidator validator = new AnalysisUploadValidator();
    @InjectMocks DashboardService service;

    @BeforeEach
    void user() {
        TransactionSynchronizationManager.initSynchronization();
        when(users.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(User.builder().email("admin@example.com").build()));
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void invalidUploadCreatesNoDatabaseRecordsOrAiRequest() {
        var file = new MockMultipartFile("file", "bad.xlsx", null, new byte[]{1});
        assertThrows(CustomException.class,
                () -> service.uploadAndStartAnalysis(file, "admin@example.com", null, null, true, false, null, null));
        verifyNoInteractions(uploads, jobs, client);
    }

    @Test
    void xlsxKeepsOriginalMetadataAndDispatchesCsvWithOptions() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("번호");
            header.createCell(1).setCellValue("질문");
            header.createCell(2).setCellValue("날짜");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue("안녕하세요");
            row.createCell(2).setCellValue("2026-01-02");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        when(uploads.findByFileHash(anyString())).thenReturn(Optional.empty());
        var file = new MockMultipartFile("file", "원본.xlsx", null, bytes);
        var response = service.uploadAndStartAnalysis(file, "admin@example.com", "2026-01-01", "2026-01-31", false, true, 3, 1);
        assertEquals("원본.xlsx", response.getFileName());
        var saved = ArgumentCaptor.forClass(UploadFile.class);
        verify(uploads).save(saved.capture());
        assertEquals("원본.xlsx", saved.getValue().getOriginalFileName());
        assertEquals(bytes.length, saved.getValue().getFileSize());
        verifyNoInteractions(client);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        var sent = ArgumentCaptor.forClass(byte[].class);
        verify(client).sendPipeline(anyString(), isNull(), sent.capture(), eq("원본.csv"), eq(false), eq(true), eq("2026-01-01"), eq("2026-01-31"), eq(3), eq(1), any());
        assertTrue(new String(sent.getValue(), StandardCharsets.UTF_8).contains("안녕하세요"));
        // 보관은 AI로 보낸 CSV가 아니라 변환 전 xlsx 원본으로 한다
        verify(archive).archive(anyString(), eq(bytes), eq("원본.xlsx"));
    }
}
