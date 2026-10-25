package com.breathAI.ttobagi_server.domain.faq.repository;

import com.breathAI.ttobagi_server.domain.faq.entity.FaqEditHistory;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersion;
import com.breathAI.ttobagi_server.domain.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query; 
import org.springframework.data.repository.query.Param;

import java.util.List;

// FAQ 수정 이력 리포지토리
public interface FaqEditHistoryRepository extends JpaRepository<FaqEditHistory, Long> {

    // FAQ 단위 수정 이력 조회
    List<FaqEditHistory> findByFaq_FaqId(Long faqId);
    // FAQ 단위 수정 이력 최신순 조회
    List<FaqEditHistory> findByFaq_FaqIdOrderByCreatedAtDesc(Long faqId);

    // 분석 단위 수정 이력 조회
    List<FaqEditHistory> findByAnalysisJobAnalysisId(Long analysisId);
    // 수정자 기준 이력 조회
    List<FaqEditHistory> findByEditedBy_UserId(Long userId);
    // 분석 및 수정자 복합 조회
    List<FaqEditHistory> findByAnalysisJob_AnalysisIdAndEditedBy_UserId(Long analysisId, Long userId);

    // 변경 이력 페이징 조회, faqId·editType이 null이면 그 조건을 적용하지 않는다
    @EntityGraph(attributePaths = {"faq", "analysisJob", "editedBy"})
    @Query("SELECT h FROM FaqEditHistory h WHERE (:faqId IS NULL OR h.faq.faqId = :faqId) "
            + "AND (:editType IS NULL OR h.editType = :editType)")
    Page<FaqEditHistory> search(@Param("faqId") Long faqId,
                                @Param("editType") FaqEditHistory.EditType editType,
                                Pageable pageable);

    // 아직 버전으로 묶이지 않은 변경의 유형별 건수
    @Query("SELECT h.editType, COUNT(h) FROM FaqEditHistory h WHERE h.version IS NULL GROUP BY h.editType")
    List<Object[]> countPendingByEditType();

    // 아직 버전으로 묶이지 않은 변경이 있는지 확인
    boolean existsByVersionIsNull();

    // 아직 버전으로 묶이지 않은 변경을 새 버전에 묶는다
    @Modifying
    @Query("UPDATE FaqEditHistory h SET h.version = :version WHERE h.version IS NULL")
    int assignPendingToVersion(@Param("version") FaqVersion version);

    // 버전에 묶인 변경 목록
    @EntityGraph(attributePaths = {"faq"})
    List<FaqEditHistory> findByVersion_VersionIdOrderByHistoryIdAsc(Long versionId);

    // 회원 탈퇴 시 사용자 참조 해제
    @Modifying
    @Query("UPDATE FaqEditHistory f SET f.editedBy = null WHERE f.editedBy = :user")
    void clearEditedBy(@Param("user") User user);
}