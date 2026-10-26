package com.breathAI.ttobagi_server.domain.dashboard.repository;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisHistory;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob;
import com.breathAI.ttobagi_server.domain.dashboard.entity.AnalysisJob.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// 사용자별 분석 요청 이력 리포지토리, 목록 조회 시 요청자와 분석을 함께 로딩
// status가 null이면 상태와 관계없이 조회한다
public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, Long> {

    @EntityGraph(attributePaths = {"user", "analysisJob"})
    @Query("SELECT h FROM AnalysisHistory h WHERE h.user = :user "
            + "AND (:status IS NULL OR h.analysisJob.status = :status) "
            + "ORDER BY h.requestedAt DESC, h.historyId DESC")
    Page<AnalysisHistory> findMine(@Param("user") User user, @Param("status") Status status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "analysisJob"})
    @Query("SELECT h FROM AnalysisHistory h "
            + "WHERE (:status IS NULL OR h.analysisJob.status = :status) "
            + "ORDER BY h.requestedAt DESC, h.historyId DESC")
    Page<AnalysisHistory> findAllRequested(@Param("status") Status status, Pageable pageable);

    Optional<AnalysisHistory> findByUserAndAnalysisJob(User user, AnalysisJob analysisJob);

    Optional<AnalysisHistory> findByUserAndAnalysisJob_AnalysisId(User user, Long analysisId);

    // 회원 탈퇴 시 사용
    @Modifying
    @Query("DELETE FROM AnalysisHistory h WHERE h.user = :user")
    void deleteByUser(@Param("user") User user);
}
