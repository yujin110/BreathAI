ALTER TABLE gold_faq_candidate_match
    MODIFY COLUMN match_score DECIMAL(5,4) NULL COMMENT '매칭 유사도 점수';
