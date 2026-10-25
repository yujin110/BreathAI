package com.breathAI.ttobagi_server.global.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.function.Consumer;

// AI 서버 호출 담당 유틸
@Slf4j
@Component
@RequiredArgsConstructor
public class WebClientUtil {

    // AI 서버는 파일을 받으면 바로 접수 응답을 주므로, 이 시간 안에 응답이 없으면 실패로 본다
    // 최대 50MB 파일 전송 시간을 고려해 넉넉히 잡았다
    private static final Duration PIPELINE_REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final WebClient webClient;

    // 업로드 파일을 AI 서버로 전달하여 분석 파이프라인 시작
    // 결과는 응답이 아닌 별도 콜백으로 수신하고, 요청 자체가 실패하면 onFailure를 실행한다
    @Async
    public void sendPipeline(
            String uploadId,
            Long analysisId,
            byte[] fileBytes,
            String fileName,
            Boolean isMaskingEnabled,
            Boolean isTranslationEnabled,
            String periodStartDate,
            String periodEndDate,
            Integer minClusterSize,
            Integer minSamples,
            Consumer<String> onFailure) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("uploadId", uploadId);
            body.add("analysisId", analysisId.toString());
            body.add("isMaskingEnabled", isMaskingEnabled != null ? isMaskingEnabled.toString() : "true");
            body.add("isTranslationEnabled", isTranslationEnabled != null ? isTranslationEnabled.toString() : "false");
            body.add("periodStartDate", periodStartDate != null ? periodStartDate : "");
            body.add("periodEndDate", periodEndDate != null ? periodEndDate : "");
            // 군집화 설정은 값이 있을 때만 보내고, 없으면 AI 서버 기본값을 쓴다
            if (minClusterSize != null) body.add("minClusterSize", minClusterSize.toString());
            if (minSamples != null) body.add("minSamples", minSamples.toString());
            body.add("file", new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });

            // 연결 실패, 4xx·5xx 응답, 시간 초과를 모두 실패로 처리한다
            webClient.post()
                    .uri("/pipeline")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(PIPELINE_REQUEST_TIMEOUT)
                    .subscribe(
                            response -> log.info("AI 서버 파이프라인 요청 접수 analysisId: {}, status: {}",
                                    analysisId, response.getStatusCode()),
                            e -> {
                                log.error("AI 서버 파이프라인 요청 실패 analysisId: {}, uploadId: {}", analysisId, uploadId, e);
                                onFailure.accept(failureReason(e));
                            });

        } catch (Exception e) {
            log.error("AI 서버 파이프라인 요청 실패 analysisId: {}, uploadId: {}", analysisId, uploadId, e);
            onFailure.accept(failureReason(e));
        }
    }

    // 분석 실패 사유, AI가 입력 파일 검사에서 거절(422)한 경우는 연결 문제와 구분한다
    static String failureReason(Throwable e) {
        if (e instanceof WebClientResponseException response && response.getStatusCode().value() == 422) {
            return "AI 서버가 파일 내용을 받지 않았습니다. 번호(겹치지 않는 정수), 질문, 날짜(예: 2026-08-27 12:04:00) 열을 확인해 주세요.";
        }
        return "AI 서버에 분석을 요청하지 못했습니다.";
    }
}
