-- 사용자별 분석 요청 이력
-- 같은 파일을 다른 사람이 올리면 분석은 다시 하지 않고 요청 이력만 남긴다
-- 이력 삭제는 이 행만 지우며 분석 데이터는 그대로 둔다

CREATE TABLE `gold_analysis_history` (
  `history_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '분석을 요청한 사용자 ID',
  `analysis_id` bigint(20) NOT NULL COMMENT '요청한 분석 ID',
  `requested_at` datetime(6) NOT NULL DEFAULT current_timestamp(6) COMMENT '마지막 요청 시각',
  PRIMARY KEY (`history_id`),
  UNIQUE KEY `uq_history_user_analysis` (`user_id`, `analysis_id`),
  KEY `fk_history_analysis_id` (`analysis_id`),
  CONSTRAINT `fk_history_user_id` FOREIGN KEY (`user_id`) REFERENCES `gold_user` (`user_id`),
  CONSTRAINT `fk_history_analysis_id` FOREIGN KEY (`analysis_id`) REFERENCES `gold_analysis_job` (`analysis_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='사용자별 분석 요청 이력';

-- 기존 분석은 업로더를 요청자로 채운다 (탈퇴로 업로더가 비어 있으면 채우지 않는다)
INSERT INTO `gold_analysis_history` (`user_id`, `analysis_id`, `requested_at`)
SELECT u.`uploaded_by`, a.`analysis_id`, a.`created_at`
FROM `gold_analysis_job` a
JOIN `gold_upload_file` u ON u.`upload_id` = a.`upload_id`
WHERE u.`uploaded_by` IS NOT NULL;

-- 분석에 쓴 군집화 설정, 비어 있으면 AI 서버 기본값을 썼다는 뜻이다
ALTER TABLE `gold_analysis_job`
  ADD COLUMN `min_cluster_size` int(11) DEFAULT NULL COMMENT 'HDBSCAN 최소 클러스터 크기 (NULL=AI 기본값)',
  ADD COLUMN `min_samples` int(11) DEFAULT NULL COMMENT 'HDBSCAN 최소 샘플 수 (NULL=AI 기본값)';
