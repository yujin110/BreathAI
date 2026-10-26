-- 회원가입 이메일 인증 코드
-- 가입 전에 발급하므로 계정이 아니라 이메일 기준으로 둔다. 가입에 성공하면 지운다
CREATE TABLE `gold_signup_verification` (
  `verification_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `email` varchar(255) NOT NULL COMMENT '인증할 이메일 (소문자)',
  `code` varchar(20) NOT NULL COMMENT '메일로 보낸 6자리 인증 코드 (대소문자 구분)',
  `attempt_count` int(11) NOT NULL DEFAULT 0 COMMENT '인증 코드 입력 실패 횟수',
  `expiry_date` datetime(6) NOT NULL COMMENT '만료 시각',
  `sent_at` datetime(6) NOT NULL COMMENT '코드를 보낸 시각 (다시 받기 간격 확인용)',
  PRIMARY KEY (`verification_id`),
  UNIQUE KEY `uq_signup_verification_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원가입 이메일 인증 코드';

-- API 호출 감사로그
-- 증빙용이라 탈퇴해도 남기도록 계정과 연결하지 않고 이메일을 글자로 저장한다. 요청 본문은 저장하지 않는다
CREATE TABLE `gold_audit_log` (
  `log_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `email` varchar(255) DEFAULT NULL COMMENT '요청한 사용자 이메일 (토큰 또는 로그인 요청 이메일, 없으면 NULL)',
  `method` varchar(10) NOT NULL COMMENT 'HTTP 메서드',
  `path` varchar(500) NOT NULL COMMENT '요청 경로',
  `query` varchar(1000) DEFAULT NULL COMMENT '쿼리 문자열',
  `status` int(11) NOT NULL COMMENT '응답 상태 코드',
  `is_success` tinyint NOT NULL COMMENT '성공 여부 (상태 코드 400 미만, 1/0)',
  `ip` varchar(64) DEFAULT NULL COMMENT '접속 IP',
  `duration_ms` bigint(20) NOT NULL COMMENT '처리 시간 (ms)',
  `created_at` datetime(6) NOT NULL DEFAULT current_timestamp(6),
  PRIMARY KEY (`log_id`),
  KEY `idx_audit_log_created_at` (`created_at`),
  KEY `idx_audit_log_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API 호출 감사로그';
