package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.dashboard.dto.AnalysisProgressResponse;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisProgress;
import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisJobRepository;
import com.breathAI.ttobagi_server.global.util.SseEmitterManager;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// AI 분석 세부 진행 정보: 진행 중인 분석을 주기적으로 AI에 물어 저장하고 SSE로 보낸다
// 완료·실패 판정은 콜백으로 한다, AI에서 작업이 사라진 분석만 여기서 실패·취소로 끝낸다
@Slf4j
@Service
public class AnalysisProgressService {

    // 이 시간 넘게 진행 정보가 바뀌지 않으면 지연으로 표시한다
    static final Duration DELAY_THRESHOLD = Duration.ofSeconds(60);
    static final String LOST_JOB_MESSAGE = "AI 서버에서 이 분석 작업을 찾을 수 없습니다. 다시 업로드해 주세요.";
    private static final List<AnalysisJob.Status> RUNNING = List.of(
            AnalysisJob.Status.PENDING, AnalysisJob.Status.PREPROCESSING, AnalysisJob.Status.ANALYZING);

    private final AnalysisJobRepository jobs;
    private final WebClientUtil ai;
    private final SseEmitterManager sse;
    private final TransactionTemplate transactions;
    // AI가 작업을 모른다(404)고 한 뒤 이 시간 동안 계속 모르면 분석을 끝낸다
    private final Duration lostJobGrace;
    // 분석별로 AI 404를 처음 받은 시각, 서버를 다시 켜면 처음부터 다시 잰다
    private final Map<Long, LocalDateTime> missingSince = new ConcurrentHashMap<>();

    public AnalysisProgressService(AnalysisJobRepository jobs, WebClientUtil ai,
            SseEmitterManager sse, PlatformTransactionManager manager,
            @Value("${analysis.lost-job.grace-ms:180000}") long lostJobGraceMs) {
        this.jobs = jobs;
        this.ai = ai;
        this.sse = sse;
        this.transactions = new TransactionTemplate(manager);
        this.lostJobGrace = Duration.ofMillis(lostJobGraceMs);
    }

    @Scheduled(fixedDelayString = "${analysis.progress.poll-delay-ms:10000}",
            initialDelayString = "${analysis.progress.poll-delay-ms:10000}")
    public void poll() {
        poll(LocalDateTime.now());
    }

    // AI가 접수한 진행 중 분석의 진행 정보를 가져와 저장하고, 구독자에게 현재 값을 보낸다
    // AI 호출 중에는 행 잠금을 잡지 않는다, 시험에서는 시각을 넘겨 부른다
    public void poll(LocalDateTime now) {
        List<Long> targets = jobs.findAcceptedIds(RUNNING);
        missingSince.keySet().retainAll(targets);
        for (Long id : targets) {
            WebClientUtil.AiJob aiJob;
            try {
                aiJob = ai.job(id);
                missingSince.remove(id);
            } catch (WebClientResponseException error) {
                if (error.getStatusCode().value() == 404) {
                    handleMissing(id, now);
                    continue;
                }
                log.warn("AI 진행 정보 조회 실패: analysisId={}, status={}", id, error.getStatusCode().value());
                aiJob = null;
            } catch (Exception error) {
                // 연결 실패·시간 초과는 AI에 작업이 있는지 알 수 없으므로 404 판단을 그대로 둔다
                log.warn("AI 진행 정보 조회 실패: analysisId={}, error={}", id, error.getClass().getSimpleName());
                aiJob = null;
            }
            AnalysisProgress progress = aiJob != null ? aiJob.progress() : null;
            // 취소를 요청한 분석은 진행 정보를 저장·전송하지 않는다
            AnalysisProgressResponse current = transactions.execute(tx -> jobs.findByIdForUpdate(id)
                    .filter(job -> !job.isCancellationRequested())
                    .map(job -> {
                        job.recordProgress(progress, now);
                        return toResponse(job, now);
                    })
                    .orElse(null));
            if (current != null) sse.sendProgress(id, current);
        }
    }

    // AI에 작업이 없을 때: 처음이면 시각만 남기고, 기준 시간이 지나도 계속 없으면 분석을 끝낸다
    // 취소를 요청한 분석은 취소로, 아니면 실패로 끝낸다 (이후 온 콜백은 저장하지 않는다)
    private void handleMissing(Long id, LocalDateTime now) {
        LocalDateTime since = missingSince.putIfAbsent(id, now);
        if (since == null) {
            log.warn("AI에 분석 작업 없음, 계속 없으면 종료: analysisId={}", id);
            return;
        }
        if (Duration.between(since, now).compareTo(lostJobGrace) < 0) return;
        missingSince.remove(id);
        AnalysisJob.Status result = transactions.execute(tx -> jobs.findByIdForUpdate(id)
                .filter(job -> !job.getStatus().isFinished())
                .map(job -> {
                    if (job.isCancellationRequested()) job.cancel();
                    else job.fail(LOST_JOB_MESSAGE);
                    return job.getStatus();
                })
                .orElse(null));
        if (result == AnalysisJob.Status.CANCELED) {
            log.info("AI에 없는 분석 취소 확정: analysisId={}", id);
            sse.send(id, "CANCELED", "분석이 취소되었습니다.");
        } else if (result == AnalysisJob.Status.FAIL) {
            log.warn("AI에 없는 분석 실패 처리: analysisId={}", id);
            sse.send(id, "FAIL", "분석에 실패했습니다.");
        }
    }

    // 저장된 진행 정보를 응답으로 변환, 기록이 없으면 null
    // 끝났거나 취소를 요청한 분석은 더 바뀌지 않으므로 지연으로 보지 않는다
    public AnalysisProgressResponse toResponse(AnalysisJob job, LocalDateTime now) {
        AnalysisProgress progress = job.getProgress();
        if (progress == null) return null;
        boolean delayed = !job.getStatus().isFinished() && !job.isCancellationRequested()
                && job.getProgressUpdatedAt() != null
                && Duration.between(job.getProgressUpdatedAt(), now).compareTo(DELAY_THRESHOLD) > 0;
        return AnalysisProgressResponse.builder()
                .phase(progress.getPhase())
                .completedUnits(progress.getCompletedUnits())
                .totalUnits(progress.getTotalUnits())
                .unit(progress.getUnit())
                .elapsedSeconds(progress.getElapsedSeconds())
                .sequence(progress.getSequence())
                .updatedAt(job.getProgressUpdatedAt())
                .delayed(delayed)
                .build();
    }
}
