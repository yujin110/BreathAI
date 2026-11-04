-- 분석 취소: 취소 상태(CANCELED)와 분석을 시작한 사람을 추가한다
-- 같은 파일은 업로드 기록을 다시 쓰므로 uploaded_by로는 분석마다 시작한 사람을 알 수 없다
-- 예전 분석의 requested_by는 비워 둔다(모두 끝난 분석이라 취소할 일이 없다)
ALTER TABLE `gold_analysis_job`
  MODIFY COLUMN `status` varchar(20) NOT NULL COMMENT 'PENDING, PREPROCESSING, ANALYZING, COMPLETED, FAIL, CANCELED',
  ADD COLUMN `requested_by` bigint(20) DEFAULT NULL COMMENT '분석을 시작한 사용자 (탈퇴 시 NULL)' AFTER `upload_id`,
  ADD CONSTRAINT `fk_analysis_requested_by` FOREIGN KEY (`requested_by`) REFERENCES `gold_user` (`user_id`);
