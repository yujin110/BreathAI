package com.breathAI.ttobagi_server.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientUtilTest {

    @Test
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
