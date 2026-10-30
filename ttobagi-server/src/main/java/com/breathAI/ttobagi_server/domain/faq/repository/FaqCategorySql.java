package com.breathAI.ttobagi_server.domain.faq.repository;

// 카테고리 마스터를 읽는 네이티브 쿼리 조각
// 기존 카테고리(bronze_cate_info, 코드별 최신 이름)와 BE에서 만든 카테고리(gold_faq_category)를 합친다
// 결과 컬럼: q_type, q_disp_name
public final class FaqCategorySql {

    public static final String MASTER = "(SELECT b.q_type, b.q_disp_name FROM ("
            + "  SELECT q_type, q_disp_name, ROW_NUMBER() OVER ("
            + "    PARTITION BY q_type ORDER BY _ingested_at DESC, seq_num DESC) AS rn"
            + "  FROM bronze_cate_info) b WHERE b.rn = 1 "
            + "UNION ALL SELECT q_type, name FROM gold_faq_category)";

    private FaqCategorySql() {
    }
}
