-- 분석 모드와 분석 범위 (대용량 분석 개선, AI 연동 계약 v2)
-- 모드가 생기기 전 분석은 전체 유형을 분석했으므로 ALL로 채운다
ALTER TABLE `gold_analysis_job`
  ADD COLUMN `analysis_mode` varchar(20) NOT NULL DEFAULT 'ALL' COMMENT 'IMPROVEMENT, UNANSWER_ONLY, ALL (실제 적용 모드)' AFTER `min_samples`,
  ADD COLUMN `result_schema_version` int(11) DEFAULT NULL COMMENT 'AI 결과 형식 버전, 비어 있으면 1' AFTER `analysis_mode`,
  ADD COLUMN `completion_reason` varchar(40) DEFAULT NULL COMMENT '완료 사유 (RESULTS_GENERATED, NO_TARGET_LOGS 등)' AFTER `result_schema_version`,
  ADD COLUMN `analysis_scope` json DEFAULT NULL COMMENT 'AI가 보낸 분석 범위 건수' AFTER `completion_reason`;
