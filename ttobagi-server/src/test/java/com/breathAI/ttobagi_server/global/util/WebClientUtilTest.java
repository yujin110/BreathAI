package com.breathAI.ttobagi_server.global.util;

import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisMode;
import com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisSubmissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebClientUtilTest {

    @Test
    @DisplayName("취소·상태 조회는 기존 인증 토큰으로 /jobs/{id} 경로를 부른다")
    void cancelUsesExistingBearerCredentialAndJobPath() {
        var requests = new java.util.ArrayList<org.springframework.web.reactive.function.client.ClientRequest>();
        var client = org.springframework.web.reactive.function.client.WebClient.builder()
                .baseUrl("https://ai.example")
                .defaultHeader("Authorization", "Bearer existing-token")
                .exchangeFunction(request -> {
                    requests.add(request);
                    return reactor.core.publisher.Mono.just(
                            org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                                    .header("Content-Type", "application/json")
                                    .body("{\"status\":\"CANCELED\"}").build());
                }).build();
        var utility = new WebClientUtil(client, org.mockito.Mockito.mock(
                com.breathAI.ttobagi_server.domain.dashboard.service.AnalysisSubmissionService.class));
        utility.cancelJob(123L);
        assertThat(utility.jobStatus(123L)).isEqualTo("CANCELED");
        assertThat(requests.get(0).method()).isEqualTo(org.springframework.http.HttpMethod.POST);
        assertThat(requests.get(0).url().getPath()).isEqualTo("/jobs/123/cancel");
        assertThat(requests.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer existing-token");
        assertThat(requests.get(1).method()).isEqualTo(org.springframework.http.HttpMethod.GET);
        assertThat(requests.get(1).url().getPath()).isEqualTo("/jobs/123");
    }

    @Test
    @DisplayName("분석 요청 본문에 분석 모드를 넣어 보낸다")
    void pipelineRequestCarriesAnalysisMode() {
        var body = new AtomicReference<String>();
        var client = WebClient.builder()
                .baseUrl("https://ai.example")
                .exchangeFunction(request -> {
                    var sent = new MockClientHttpRequest(request.method(), request.url());
                    return request.writeTo(sent, ExchangeStrategies.withDefaults())
                            .then(Mono.defer(sent::getBodyAsString))
                            .doOnNext(body::set)
                            .thenReturn(ClientResponse.create(HttpStatus.ACCEPTED).build());
                }).build();
        var submissions = mock(AnalysisSubmissionService.class);
        when(submissions.begin(1L)).thenReturn(true);

        new WebClientUtil(client, submissions).sendPipeline("upload-1", 1L, "번호,질문,날짜\n".getBytes(StandardCharsets.UTF_8),
                "data.csv", true, false, "", "", null, null, AnalysisMode.UNANSWER_ONLY, reason -> {});

        verify(submissions, timeout(5000)).received(1L);
        assertThat(body.get()).contains("name=\"analysisMode\"").contains("UNANSWER_ONLY");
    }

    @Test
    @DisplayName("AI가 입력 파일을 거절(422)하면 연결 실패와 다른 사유를 남긴다")
    void inputRejectionIsDistinguishedFromConnectionFailure() {
        var rejected = new WebClientResponseException(422, "Unprocessable Content", null, null, null);

        assertThat(WebClientUtil.failureReason(rejected)).contains("파일 내용").contains("날짜");
        assertThat(WebClientUtil.failureReason(new ConnectException("refused")))
                .isEqualTo("AI 서버에 분석을 요청하지 못했습니다.");
        assertThat(WebClientUtil.failureReason(new WebClientResponseException(500, "Server Error", null, null, null)))
                .isEqualTo("AI 서버에 분석을 요청하지 못했습니다.");
    }
}
