package com.breathAI.ttobagi_server.domain.faq.repository;

import com.breathAI.ttobagi_server.domain.faq.entity.FaqVersionItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

// 버전별 FAQ 사본 리포지토리
public interface FaqVersionItemRepository extends JpaRepository<FaqVersionItem, Long> {

    // 현재 활성 FAQ 전체를 해당 버전의 사본으로 적재
    @Modifying
    @Query(value = "INSERT INTO gold_faq_version_item "
            + "(version_id, faq_id, q_type, category, question, answer, keywords, faq_created_at) "
            + "SELECT :versionId, f.faq_id, f.q_type, f.category, f.question, f.answer, f.keywords, f.created_at "
            + "FROM gold_faq f WHERE f.is_active = 1",
            nativeQuery = true)
    int snapshotActiveFaqs(@Param("versionId") Long versionId);

    // 버전의 FAQ 목록 검색, 조건의 의미는 운영 FAQ 검색과 같다
    @Query("SELECT i FROM FaqVersionItem i WHERE i.version.versionId = :versionId "
            + "AND (:filterByQType = false OR i.qType IN :qTypes) "
            + "AND (:keyword IS NULL "
            + "     OR i.question LIKE CONCAT('%', :keyword, '%') "
            + "     OR i.answer LIKE CONCAT('%', :keyword, '%') "
            + "     OR i.keywords LIKE CONCAT('%', :keyword, '%'))")
    Page<FaqVersionItem> search(@Param("versionId") Long versionId,
                                @Param("filterByQType") boolean filterByQType,
                                @Param("qTypes") Collection<Integer> qTypes,
                                @Param("keyword") String keyword,
                                Pageable pageable);

    long countByVersion_VersionId(Long versionId);

    // 버전 사본 전체, 복원과 버전 비교에 쓴다
    List<FaqVersionItem> findByVersion_VersionId(Long versionId);

    // 다운로드 파일에 넣을 버전의 FAQ 전체
    // 카테고리는 마스터의 표시명으로 바꾸고, 마스터에 없는 코드면 사본에 남은 카테고리를 쓴다
    @Query(value = "SELECT i.faq_id AS faqId, "
            + "COALESCE((SELECT c.q_disp_name FROM bronze_cate_info c WHERE c.q_type = i.q_type "
            + "          ORDER BY c._ingested_at DESC LIMIT 1), i.category) AS categoryName, "
            + "i.question AS question, i.answer AS answer, i.keywords AS keywords "
            + "FROM gold_faq_version_item i WHERE i.version_id = :versionId ORDER BY i.faq_id",
            nativeQuery = true)
    List<ExportRow> findExportRows(@Param("versionId") Long versionId);

    interface ExportRow {
        Long getFaqId();
        String getCategoryName();
        String getQuestion();
        String getAnswer();
        String getKeywords();
    }
}
