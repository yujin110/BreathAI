package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.dashboard.dto.AnalyzeStatusResponse;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisJobRepository;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.breathAI.ttobagi_server.global.util.SseEmitterManager;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Slf4j
@Service
public class AnalysisCancellationService {
    private final AnalysisJobRepository jobs;
    private final UserRepository users;
    private final WebClientUtil ai;
    private final SseEmitterManager sse;
    private final TransactionTemplate transactions;

    public AnalysisCancellationService(AnalysisJobRepository jobs, UserRepository users,
            WebClientUtil ai, SseEmitterManager sse, PlatformTransactionManager manager) {
        this.jobs = jobs;
        this.users = users;
        this.ai = ai;
        this.sse = sse;
        this.transactions = new TransactionTemplate(manager);
    }

    public AnalyzeStatusResponse cancel(Long id, String email) {
        transactions.executeWithoutResult(tx -> {
            var user = users.findByEmail(email)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            var job = jobs.findByIdForUpdate(id)
                    .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_NOT_FOUND));
            if (!job.isRequestedBy(user)) throw new CustomException(ErrorCode.ACCESS_DENIED);
            if (job.getStatus().isFinished()) throw new CustomException(ErrorCode.ANALYSIS_NOT_CANCELABLE);
            job.requestCancellation();
        });
        return attempt(id);
    }

    private AnalyzeStatusResponse attempt(Long id) {
        String submission = transactions.execute(tx -> {
            var job = jobs.findByIdForUpdate(id).orElseThrow();
            if (job.getStatus().isFinished() && job.getStatus() != AnalysisJob.Status.CANCELED)
                throw new CustomException(ErrorCode.ANALYSIS_NOT_CANCELABLE);
            return job.getStatus() == AnalysisJob.Status.CANCELED ? "CANCELED" : job.getAiSubmissionState();
        });
        // PENDING is fenced by submission.begin(): the upload cannot start after this intent.
        if (!"PENDING".equals(submission) && !"REJECTED".equals(submission) && !"CANCELED".equals(submission)) {
            try {
                ai.cancelJob(id);
            } catch (WebClientResponseException error) {
                if (error.getStatusCode().value() == 409) {
                    String actual;
                    try { actual = ai.jobStatus(id); }
                    catch (Exception lookup) { throw new CustomException(ErrorCode.ANALYSIS_CANCEL_PENDING); }
                    if (!"CANCELED".equals(actual)) {
                        if (!"COMPLETED".equals(actual) && !"FAILED".equals(actual))
                            throw new CustomException(ErrorCode.ANALYSIS_CANCEL_PENDING);
                        transactions.executeWithoutResult(tx -> jobs.findByIdForUpdate(id)
                                .ifPresent(AnalysisJob::clearCancellation));
                        throw new CustomException(ErrorCode.ANALYSIS_NOT_CANCELABLE);
                    }
                } else {
                    // In particular, 404 while a multipart upload is in flight is not success.
                    throw new CustomException(ErrorCode.ANALYSIS_CANCEL_PENDING);
                }
            } catch (Exception error) {
                throw new CustomException(ErrorCode.ANALYSIS_CANCEL_PENDING);
            }
        }
        var response = transactions.execute(tx -> {
            var job = jobs.findByIdForUpdate(id).orElseThrow();
            if (job.getStatus().isFinished() && job.getStatus() != AnalysisJob.Status.CANCELED)
                throw new CustomException(ErrorCode.ANALYSIS_NOT_CANCELABLE);
            job.cancel();
            return AnalyzeStatusResponse.builder()
                    .analysisId(id).uploadId(job.getUploadFile().getUploadId())
                    .status("CANCELED").message("분석이 취소되었습니다.")
                    .cancelable(false).updatedAt(job.getFinishedAt()).build();
        });
        sse.send(id, "CANCELED", "분석이 취소되었습니다.");
        return response;
    }

    @Scheduled(fixedDelayString = "${analysis.cancel.retry-delay-ms:10000}", initialDelay = 10000)
    public void recover() {
        for (Long id : jobs.findCancellationPendingIds(java.util.List.of(
                AnalysisJob.Status.CANCELED, AnalysisJob.Status.COMPLETED, AnalysisJob.Status.FAIL))) {
            try { attempt(id); }
            catch (Exception error) {
                log.warn("분석 취소 복구 대기: analysisId={}, error={}", id, error.getClass().getSimpleName());
            }
        }
    }
}
