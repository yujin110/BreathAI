package com.breathAI.ttobagi_server.domain.dashboard.service;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.auth.repository.UserRepository;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.domain.dashboard.entity.UploadFile;
import com.breathAI.ttobagi_server.domain.dashboard.repository.AnalysisJobRepository;
import com.breathAI.ttobagi_server.global.exception.CustomException;
import com.breathAI.ttobagi_server.global.exception.ErrorCode;
import com.breathAI.ttobagi_server.global.util.SseEmitterManager;
import com.breathAI.ttobagi_server.global.util.WebClientUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisCancellationServiceTest {
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final WebClientUtil ai = mock(WebClientUtil.class);
    private final SseEmitterManager sse = mock(SseEmitterManager.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private AnalysisCancellationService service;
    private AnalysisJob job;

    @BeforeEach
    void setup() {
        var user = mock(User.class);
        when(user.getUserId()).thenReturn(1L);
        var upload = mock(UploadFile.class);
        when(upload.getUploadId()).thenReturn("upload");
        job = AnalysisJob.builder().requestedBy(user).uploadFile(upload).fileName("test.csv").build();
        ReflectionTestUtils.setField(job, "analysisId", 1L);
        when(jobs.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        when(users.findByEmail("owner")).thenReturn(Optional.of(user));
        when(manager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        service = new AnalysisCancellationService(jobs, users, ai, sse, manager);
    }

    @Test
    @DisplayName("아직 보내지 않은 분석은 AI에 묻지 않고 취소하며, 이후 전송도 막는다")
    void pendingSubmissionIsFencedWithoutSendingToAi() {
        assertThat(service.cancel(1L, "owner").getStatus()).isEqualTo("CANCELED");
        assertThat(new AnalysisSubmissionService(jobs, sse).begin(1L)).isFalse();
        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("진행 중인 분석은 취소 요청을 커밋한 뒤 AI가 취소해야 CANCELED가 된다")
    void runningJobOnlySucceedsAfterAiAndCommittedIntent() {
        job.submissionState("ACCEPTED");
        doAnswer(inv -> {
            assertThat(job.isCancellationRequested()).isTrue();
            assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.PENDING);
            verify(manager, atLeastOnce()).commit(any());
            return null;
        }).when(ai).cancelJob(1L);
        assertThat(service.cancel(1L, "owner").getStatus()).isEqualTo("CANCELED");
        verify(ai).cancelJob(1L);
        verify(sse).send(1L, "CANCELED", "분석이 취소되었습니다.");
    }

    @Test
    @DisplayName("전송 중(SENDING)에 AI가 404면 성공으로 보지 않고, 복구 작업이 다시 시도한다")
    void inFlightUpload404IsNotSuccessAndRecoveryRetries() {
        job.submissionState("SENDING");
        doThrow(new WebClientResponseException(404, "not registered yet", null, null, null)).when(ai).cancelJob(1L);
        assertThatThrownBy(() -> service.cancel(1L, "owner"))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_CANCEL_PENDING));
        assertThat(job.isCancellationRequested()).isTrue();
        assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.PENDING);
        verifyNoInteractions(sse);
        doNothing().when(ai).cancelJob(1L);
        when(jobs.findCancellationPendingIds(anyList(), any())).thenReturn(List.of(1L));
        service.recover();
        assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.CANCELED);
    }

    @Test
    @DisplayName("전송이 끝났는데(UNKNOWN) AI에 작업이 없으면(404) 취소 완료로 처리한다")
    void finishedSubmissionUnknownToAiIsCanceled() {
        job.submissionState("UNKNOWN");
        doThrow(new WebClientResponseException(404, "Unknown analysisId", null, null, null)).when(ai).cancelJob(1L);
        assertThat(service.cancel(1L, "owner").getStatus()).isEqualTo("CANCELED");
        assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.CANCELED);
        verify(sse).send(1L, "CANCELED", "분석이 취소되었습니다.");
    }

    @Test
    @DisplayName("AI 응답을 알 수 없는 실패는 취소 성공으로 알리지 않는다")
    void ambiguousFailureDoesNotReportSuccess() {
        job.submissionState("UNKNOWN");
        doThrow(new IllegalStateException("timeout")).when(ai).cancelJob(1L);
        assertThatThrownBy(() -> service.cancel(1L, "owner")).isInstanceOf(CustomException.class);
        assertThat(job.getStatus()).isEqualTo(AnalysisJob.Status.PENDING);
        assertThat(job.isCancellationRequested()).isTrue();
    }

    @Test
    @DisplayName("AI가 409면 실제 상태를 조회하고, 이미 취소됐으면 CANCELED로 확정한다")
    void recoveryOfAlreadyCanceledAiJobChecksActualStatus() {
        job.submissionState("ACCEPTED");
        doThrow(new WebClientResponseException(409, "finished", null, null, null)).when(ai).cancelJob(1L);
        when(ai.jobStatus(1L)).thenReturn("CANCELED");
        assertThat(service.cancel(1L, "owner").getStatus()).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("AI에서 이미 완료됐으면 409를 주고 취소 요청을 지운다")
    void completedAiJobReturnsConflictAndClearsIntent() {
        job.submissionState("ACCEPTED");
        doThrow(new WebClientResponseException(409, "finished", null, null, null)).when(ai).cancelJob(1L);
        when(ai.jobStatus(1L)).thenReturn("COMPLETED");
        assertThatThrownBy(() -> service.cancel(1L, "owner"))
                .isInstanceOfSatisfying(CustomException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_NOT_CANCELABLE));
        assertThat(job.isCancellationRequested()).isFalse();
        verifyNoInteractions(sse);
    }

    @Test
    @DisplayName("이미 취소된 분석을 다시 취소하면 AI에 묻지 않고 409이다")
    void alreadyCanceledUserRequestReturnsConflict() {
        job.cancel();
        assertThatThrownBy(() -> service.cancel(1L, "owner")).isInstanceOf(CustomException.class);
        verifyNoInteractions(ai);
    }
}
