-- FAQ 유사어를 키워드처럼 저장한다
-- 지금까지 유사어는 후보(gold_synonym_candidate)에만 있어 승인하면 사라졌다
-- 기존 FAQ·버전·이력은 유사어 없음(NULL)으로 시작한다
ALTER TABLE `gold_faq`
  ADD COLUMN `synonyms` json DEFAULT NULL COMMENT '["분실물센터", "유실물센타"]' AFTER `keywords`;

ALTER TABLE `gold_faq_version_item`
  ADD COLUMN `synonyms` json DEFAULT NULL COMMENT '유사어 배열' AFTER `keywords`;

ALTER TABLE `gold_faq_edit_history`
  ADD COLUMN `before_synonyms` json DEFAULT NULL COMMENT '수정 전 유사어' AFTER `before_keywords`,
  ADD COLUMN `after_synonyms` json DEFAULT NULL COMMENT '수정 후 유사어' AFTER `after_keywords`;
