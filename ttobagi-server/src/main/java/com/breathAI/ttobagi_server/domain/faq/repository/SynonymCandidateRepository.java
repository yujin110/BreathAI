package com.breathAI.ttobagi_server.domain.faq.repository;

import com.breathAI.ttobagi_server.domain.faq.entity.FaqCandidate;
import com.breathAI.ttobagi_server.domain.faq.entity.SynonymCandidate;
import com.breathAI.ttobagi_server.domain.faq.entity.SynonymCandidate.SynonymType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 유사어 후보 리포지토리
public interface SynonymCandidateRepository extends JpaRepository<SynonymCandidate, Long> {

    // 특정 FAQ 후보에 연결된 모든 유사어 조회
    List<SynonymCandidate> findByCandidate(FaqCandidate candidate);
    List<SynonymCandidate> findByCandidateCandidateId(Long candidateId);

    // 저장한 순서대로, 정렬이 없으면 유니크 인덱스(글자순)대로 나온다
    List<SynonymCandidate> findByCandidateCandidateIdOrderBySynonymIdAsc(Long candidateId);

    // 유형별 필터링
    List<SynonymCandidate> findBySynonymType(SynonymType synonymType);

    // 유사어 텍스트 키워드 검색
    List<SynonymCandidate> findBySynonymTextContaining(String keyword);

    // 저장 전 중복 체크용
    boolean existsByCandidateCandidateIdAndSynonymText(Long candidateId, String synonymText);

}