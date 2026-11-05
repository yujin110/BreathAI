package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.dashboard.dto.AnalysisProgressResponse;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisProgress;
import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisJobRepository;
import com.breathAI.ttobagi_server.global.util.SseEmitterManager;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// AI 분석 세부 진행 정보: 진행 중인 분석을 주기적으로 AI에 물어 저장하고 SSE로 보낸다
// 상태(완료·실패)는 바꾸지 않는다, 그 판정은 콜백으로만 한다
@Slf4j
@Service
public class AnalysisProgressService {

    // 이 시간 넘게 진행 정보가 바뀌지 않으면 지연으로 표시한다
    static final Duration DELAY_THRESHOLD = Duration.ofSeconds(60);
    private static final List<AnalysisJob.Status> RUNNING = List.of(
            AnalysisJob.Status.PENDING, AnalysisJob.Status.PREPROCESSING, AnalysisJob.Status.ANALYZING);

    private final AnalysisJobRepository jobs;
    private final WebClientUtil ai;
    private final SseEmitterManager sse;
    private final TransactionTemplate transactions;

    public AnalysisProgressService(AnalysisJobRepository jobs, WebClientUtil ai,
            SseEmitterManager sse, PlatformTransactionManager manager) {
        this.jobs = jobs;
        this.ai = ai;
        this.sse = sse;
        this.transactions = new TransactionTemplate(manager);
    }

    // AI가 접수한 진행 중 분석의 진행 정보를 가져와 저장하고, 구독자에게 현재 값을 보낸다
    // AI 호출 중에는 행 잠금을 잡지 않는다
    @Scheduled(fixedDelayString = "${analysis.progress.poll-delay-ms:10000}", initialDelay = 10000)
    public void poll() {
        for (Long id : jobs.findProgressTargetIds(RUNNING)) {
            AnalysisProgress progress = fetch(id);
            AnalysisProgressResponse current = transactions.execute(tx -> jobs.findByIdForUpdate(id)
                    .map(job -> {
                        LocalDateTime now = LocalDateTime.now();
                        job.recordProgress(progress, now);
                        return toResponse(job, now);
                    })
                    .orElse(null));
            if (current != null) sse.sendProgress(id, current);
        }
    }

    // AI 응답의 진행 정보, 응답이 없거나 실패하면 null (저장된 값을 그대로 둔다)
    private AnalysisProgress fetch(Long id) {
        try {
            WebClientUtil.AiJob job = ai.job(id);
            return job != null ? job.progress() : null;
        } catch (Exception error) {
            log.warn("AI 진행 정보 조회 실패: analysisId={}, error={}", id, error.getClass().getSimpleName());
            return null;
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
