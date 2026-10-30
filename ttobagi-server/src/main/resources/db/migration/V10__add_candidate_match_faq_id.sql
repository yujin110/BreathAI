-- 확장 후보의 대상 FAQ를 원본 순번(seq)이 아니라 운영 FAQ ID로 찾는다
-- 원본 순번이 없는 FAQ(엑셀 전용, 직접 등록·승인으로 생긴 FAQ)와도 매칭을 저장할 수 있게 순번은 NULL을 허용한다
-- AI가 없는 FAQ ID를 보내도 분석 결과 저장이 실패하지 않도록 gold_faq 외래키는 걸지 않는다(승인할 때 409)
ALTER TABLE `gold_faq_candidate_match`
  ADD COLUMN `matched_faq_id` bigint(20) DEFAULT NULL COMMENT 'gold_faq.faq_id (확장 대상 FAQ)' AFTER `candidate_id`,
  MODIFY COLUMN `matched_faq_seq_num` int(11) DEFAULT NULL COMMENT 'counselling_info.seq_num (Bronze 참조)';
