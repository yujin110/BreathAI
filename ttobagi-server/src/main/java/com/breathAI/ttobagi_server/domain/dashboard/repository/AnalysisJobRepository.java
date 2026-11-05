package com.breathAI.ttobagi_server.domain.dashboard.repository;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.domain.dashboard.entity.UploadFile;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob.Status;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

// 분석 작업 리포지토리, 조회 시 업로드 파일을 함께 로딩
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {
    // 취소를 요청했지만 아직 끝나지 않은 분석, since 이후에 요청한 것만
    @Query("SELECT a.analysisId FROM AnalysisJob a WHERE a.cancelRequestedAt >= :since AND a.status NOT IN :terminal")
    List<Long> findCancellationPendingIds(@Param("terminal") List<Status> terminal, @Param("since") LocalDateTime since);

    // 상태를 바꾸기 전에 행을 잠근다, 취소와 AI 콜백이 동시에 와도 하나씩 처리된다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AnalysisJob a WHERE a.analysisId = :analysisId")
    Optional<AnalysisJob> findByIdForUpdate(@Param("analysisId") Long analysisId);

    // 회원 탈퇴 시 사용자 참조 해제
    @Modifying
    @Query("UPDATE AnalysisJob a SET a.requestedBy = null WHERE a.requestedBy = :user")
    void clearRequestedBy(@Param("user") User user);
    
    @EntityGraph(attributePaths = {"uploadFile"})
    Optional<AnalysisJob> findByUploadFileUploadId(String uploadId);

    @EntityGraph(attributePaths = {"uploadFile"})
    Page<AnalysisJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"uploadFile"})
    Optional<AnalysisJob> findFirstByStatusOrderByCreatedAtDesc(Status status);

    @EntityGraph(attributePaths = {"uploadFile"})
    Optional<AnalysisJob> findFirstByStatusOrderByFinishedAtDesc(Status status);

    @EntityGraph(attributePaths = {"uploadFile"})
    Page<AnalysisJob> findByStatusOrderByCreatedAtDesc(Status status, Pageable pageable);

    @EntityGraph(attributePaths = {"uploadFile"})
    @Query("SELECT a FROM AnalysisJob a WHERE a.status = :status " +
        "AND a.periodStartDate >= :startDate AND a.periodEndDate <= :endDate " +
        "ORDER BY a.finishedAt DESC")
    List<AnalysisJob> findByStatusAndPeriod(
            @Param("status") Status status,
            @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    // 사용자가 이력에 남겨 둔 분석만 조회 (대시보드 scope=mine)
    @EntityGraph(attributePaths = {"uploadFile"})
    @Query("SELECT a FROM AnalysisJob a WHERE a.status = :status "
        + "AND EXISTS (SELECT h FROM AnalysisHistory h WHERE h.analysisJob = a AND h.user = :user) "
        + "ORDER BY a.finishedAt DESC")
    List<AnalysisJob> findRequestedByStatus(
            @Param("user") User user,
            @Param("status") Status status,
            Pageable pageable);

    @EntityGraph(attributePaths = {"uploadFile"})
    @Query("SELECT a FROM AnalysisJob a WHERE a.status = :status "
        + "AND a.periodStartDate >= :startDate AND a.periodEndDate <= :endDate "
        + "AND EXISTS (SELECT h FROM AnalysisHistory h WHERE h.analysisJob = a AND h.user = :user) "
        + "ORDER BY a.finishedAt DESC")
    List<AnalysisJob> findRequestedByStatusAndPeriod(
            @Param("user") User user,
            @Param("status") Status status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // 같은 업로드 기록으로 돌린 분석 전체, 최신순 (옵션별 기존 결과 찾기용)
    List<AnalysisJob> findByUploadFileOrderByCreatedAtDesc(UploadFile uploadFile);

    Optional<AnalysisJob> findByAnalysisId(Long analysisId);

    Optional<AnalysisJob> findByUploadFileAndPeriodStartDateAndPeriodEndDate(
        UploadFile uploadFile, LocalDate periodStartDate, LocalDate periodEndDate);
    
        Optional<AnalysisJob> findFirstByUploadFileOrderByCreatedAtDesc(UploadFile uploadFile);

    boolean existsByStatusIn(List<Status> statuses);
}
