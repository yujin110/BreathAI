-- 감사로그에 작업 이름·분류·대상을 남긴다
-- 기록은 모든 API 호출을 그대로 두고, 화면은 분류로 주요 작업 위주로 보여 준다
ALTER TABLE `gold_audit_log`
  ADD COLUMN `action` varchar(50) DEFAULT NULL COMMENT '작업 이름 (예: FAQ 수정, 로그인)',
  ADD COLUMN `category` varchar(20) DEFAULT NULL COMMENT 'AUTH, ANALYSIS, FAQ, EXPORT, READ, AUTO',
  ADD COLUMN `target` varchar(100) DEFAULT NULL COMMENT '작업 대상 (예: FAQ #123)',
  ADD KEY `idx_audit_log_category` (`category`);
