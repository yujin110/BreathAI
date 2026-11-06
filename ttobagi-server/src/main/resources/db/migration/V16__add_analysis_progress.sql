-- 분석 세부 진행 정보 (AI GET /jobs/{id}의 progress, AI 연동 계약 v3 4절)
ALTER TABLE `gold_analysis_job`
  ADD COLUMN `progress` json DEFAULT NULL COMMENT 'AI가 보낸 마지막 진행 정보 (phase, 건수, 경과 시간, sequence)' AFTER `analysis_scope`,
  ADD COLUMN `progress_updated_at` datetime DEFAULT NULL COMMENT '진행 정보의 sequence가 마지막으로 늘어난 시각 (BE 기준, 지연 판정용)' AFTER `progress`;
