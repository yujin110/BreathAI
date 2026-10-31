package com.breathAI.ttobagi_server.domain.faq.repository;

import com.breathAI.ttobagi_server.domain.auth.entity.User;
import com.breathAI.ttobagi_server.domain.faq.entity.FaqCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// BE에서 만든 FAQ 카테고리 리포지토리
public interface FaqCategoryRepository extends JpaRepository<FaqCategory, Integer> {

    // 기존·새 카테고리 중 공백을 모두 뺀 이름이 같은 것의 수
    @Query(value = "SELECT COUNT(*) FROM " + FaqCategorySql.MASTER + " m "
            + "WHERE REGEXP_REPLACE(m.q_disp_name, '[[:space:]]+', '') = :nameKey",
            nativeQuery = true)
    long countByNameKeyInMaster(@Param("nameKey") String nameKey);

    // 기존·새 카테고리 중 공백을 모두 뺀 이름이 같은 것 (코드가 작은 것부터)
    @Query(value = "SELECT m.q_type AS code, m.q_disp_name AS name FROM " + FaqCategorySql.MASTER + " m "
            + "WHERE REGEXP_REPLACE(m.q_disp_name, '[[:space:]]+', '') = :nameKey ORDER BY m.q_type",
            nativeQuery = true)
    List<FaqRepository.CategoryRow> findByNameKeyInMaster(@Param("nameKey") String nameKey);

    // 다음 카테고리 코드, 기존·새 코드 중 가장 큰 값 다음이며 100000보다 작지 않다
    @Query(value = "SELECT GREATEST("
            + "COALESCE((SELECT MAX(q_type) FROM gold_faq_category), 0), "
            + "COALESCE((SELECT MAX(q_type) FROM bronze_cate_info), 0), 99999) + 1",
            nativeQuery = true)
    int nextCode();

    // 회원 탈퇴 시 사용자 참조 해제
    @Modifying
    @Query("UPDATE FaqCategory c SET c.createdBy = null WHERE c.createdBy = :user")
    void clearCreatedBy(@Param("user") User user);
}
