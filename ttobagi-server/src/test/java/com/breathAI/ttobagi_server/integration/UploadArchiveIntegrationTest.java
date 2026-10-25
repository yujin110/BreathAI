package com.breathAI.ttobagi_server.integration;

import com.breathAI.ttobagi_server.domain.dashboard.repository.UploadFileRepository;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 원본 보관을 켠 상태의 업로드 흐름 (저장소는 가짜, 나머지는 실제)
@SpringBootTest(properties = {"storage.enabled=true", "storage.upload-bucket=test-uploads"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadArchiveIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UploadFileRepository uploadFileRepository;

    @MockitoBean private S3Client s3Client;
    @MockitoBean private WebClientUtil webClientUtil;

    @Test
    @DisplayName("업로드하면 변환 전 원본을 보관하고 업로드 기록에 위치를 남긴다")
    void uploadArchivesOriginal() throws Exception {
        MockMultipartFile file = csv();
        String uploadId = upload(file);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, timeout(5000)).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("test-uploads");
        assertThat(request.getValue().key()).endsWith(uploadId + ".csv");

        String expected = "s3://test-uploads/" + request.getValue().key();
        assertThat(waitForFilePath(uploadId)).isEqualTo(expected);
    }

    @Test
    @DisplayName("원본 보관이 실패해도 업로드와 AI 전달은 그대로 진행된다")
    void archiveFailureDoesNotBlockAnalysis() throws Exception {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(new RuntimeException("storage down"));

        String uploadId = upload(csv());

        verify(webClientUtil, timeout(5000)).sendPipeline(eq(uploadId), any(), any(), anyString(),
                anyBoolean(), anyBoolean(), anyString(), anyString(), any(), any(), any());
        verify(s3Client, timeout(5000)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(uploadFileRepository.findByUploadId(uploadId).orElseThrow().getFilePath()).isEmpty();
    }

    private String upload(MockMultipartFile file) throws Exception {
        String email = "archive-" + UUID.randomUUID().toString().substring(0, 8) + "@seoulmetro.co.kr";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"qatest1!\",\"adminCode\":\"test-admin-code\"}"))
                .andExpect(status().isCreated());
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"qatest1!\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = JsonPath.read(login, "$.data.accessToken");
        String response = mvc.perform(multipart("/api/v1/dashboard/analyze/upload").file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(response, "$.data.uploadId");
    }

    // 보관은 비동기라 기록될 때까지 잠깐 기다린다
    private String waitForFilePath(String uploadId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            String path = uploadFileRepository.findByUploadId(uploadId).orElseThrow().getFilePath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
            Thread.sleep(100);
        }
        return "";
    }

    private MockMultipartFile csv() {
        String content = "번호,질문,날짜\n1,보관 시험 " + UUID.randomUUID() + ",2026-08-01\n";
        return new MockMultipartFile("file", "archive.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
