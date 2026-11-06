package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisJobRepository;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.global.util.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// AI 분석 요청 전송 상태 기록, 취소와 같은 행 잠금을 쓰되 HTTP 호출 중에는 잡지 않는다
@Service
@RequiredArgsConstructor
public class AnalysisSubmissionService {
    private final AnalysisJobRepository jobs;
    private final SseEmitterManager sse;

    // 전송 실패 시 대기 중인 분석을 실패로 바꾼다, 취소를 요청했거나 이미 진행된 분석은 두고
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

    // 전송 시작 표시, 취소를 요청했거나 이미 보낸 분석이면 false (보내지 않는다)
    @Transactional
    public boolean begin(Long id) {
        var job = jobs.findByIdForUpdate(id).orElseThrow();
        if (job.isCancellationRequested() || job.getStatus().isFinished()) return false;
        if (!"PENDING".equals(job.getAiSubmissionState())) return false;
        job.submissionState("SENDING");
        return true;
    }

    // AI가 접수함
    @Transactional
    public void received(Long id) {
        jobs.findByIdForUpdate(id).ifPresent(job -> job.submissionState("ACCEPTED"));
    }

    // 전송 실패, AI가 거절(4xx)했으면 REJECTED, 접수 여부를 모르면 UNKNOWN
    @Transactional
    public void failed(Long id, boolean rejected) {
        jobs.findByIdForUpdate(id).ifPresent(job -> job.submissionState(rejected ? "REJECTED" : "UNKNOWN"));
    }
}
