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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// 분석 취소, 취소 요청을 먼저 DB에 남긴 뒤 AI 작업을 멈추고 CANCELED로 확정한다
// AI 호출 중에는 행 잠금을 잡지 않는다
@Slf4j
@Service
public class AnalysisCancellationService {

    // 확인되지 않은 취소를 자동으로 다시 시도하는 기간, 지나면 사용자가 다시 눌러야 한다
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(10);

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

    // 시작한 사람의 취소 요청, 끝난 분석은 409이고 AI 확인이 안 되면 503
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

    // AI 작업을 멈추고 CANCELED로 확정한다
    private AnalyzeStatusResponse attempt(Long id) {
        String submission = transactions.execute(tx -> {
            var job = jobs.findByIdForUpdate(id).orElseThrow();
            if (job.getStatus().isFinished() && job.getStatus() != AnalysisJob.Status.CANCELED)
                throw new CustomException(ErrorCode.ANALYSIS_NOT_CANCELABLE);
            return job.getStatus() == AnalysisJob.Status.CANCELED ? "CANCELED" : job.getAiSubmissionState();
        });
        // 아직 보내지 않은(PENDING) 분석은 취소 요청을 남긴 뒤라 전송이 시작되지 않으므로 AI에 묻지 않는다
        if (!"PENDING".equals(submission) && !"REJECTED".equals(submission) && !"CANCELED".equals(submission)) {
            try {
                ai.cancelJob(id);
            } catch (WebClientResponseException error) {
                int code = error.getStatusCode().value();
                if (code == 404 && "UNKNOWN".equals(submission)) {
                    // 전송이 실패로 끝났고 AI에도 작업이 없으면 멈출 것이 없다
                    // AI가 늦게 받았더라도 CANCELED 뒤의 콜백은 저장하지 않는다
                    log.info("AI에 없는 분석 취소 확정: analysisId={}", id);
                } else if (code == 409) {
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
                    // 전송 중(SENDING)의 404는 AI에 아직 도착하지 않았을 수 있어 성공으로 보지 않는다
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

    // 확인되지 않은 취소를 주기적으로 다시 시도한다, 요청한 지 10분이 지난 것은 건너뛴다
    @Scheduled(fixedDelayString = "${analysis.cancel.retry-delay-ms:10000}", initialDelay = 10000)
    public void recover() {
        LocalDateTime since = LocalDateTime.now().minus(RECOVERY_WINDOW);
        for (Long id : jobs.findCancellationPendingIds(List.of(
                AnalysisJob.Status.CANCELED, AnalysisJob.Status.COMPLETED, AnalysisJob.Status.FAIL), since)) {
            try { attempt(id); }
            catch (Exception error) {
                log.warn("분석 취소 복구 대기: analysisId={}, error={}", id, error.getClass().getSimpleName());
            }
        }
    }
}
