package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisJobRepository;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.global.util.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Share the row lock with cancellation, but never hold it during an HTTP call.
@Service
@RequiredArgsConstructor
public class AnalysisSubmissionService {
    private final AnalysisJobRepository jobs;
    private final SseEmitterManager sse;

    @Transactional
    public void markRequestFailed(Long id, String reason) {
        jobs.findByIdForUpdate(id).ifPresent(job -> {
            if (job.isCancellationRequested() || job.getStatus() != AnalysisJob.Status.PENDING) return;
            job.fail(reason);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override public void afterCommit() { sse.send(id, "FAIL", "분석에 실패했습니다."); }
                    });
        });
    }

    @Transactional
    public boolean begin(Long id) {
        var job = jobs.findByIdForUpdate(id).orElseThrow();
        if (job.isCancellationRequested() || job.getStatus().isFinished()) return false;
        if (!"PENDING".equals(job.getAiSubmissionState())) return false;
        job.submissionState("SENDING");
        return true;
    }

    @Transactional
    public void received(Long id) {
        jobs.findByIdForUpdate(id).ifPresent(job -> job.submissionState("ACCEPTED"));
    }

    @Transactional
    public void failed(Long id, boolean rejected) {
        jobs.findByIdForUpdate(id).ifPresent(job -> job.submissionState(rejected ? "REJECTED" : "UNKNOWN"));
    }
}
