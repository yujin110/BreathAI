-- BE에서 새로 만든 FAQ 카테고리
-- 기존 카테고리는 AI가 적재하는 bronze_cate_info에 있고, 조회할 때 이 테이블과 합쳐 읽는다(복사하지 않음)
-- 새 코드는 bronze 코드(세 자리)와 겹치지 않도록 100000부터 쓴다
-- 합쳐 읽을 때 정렬 규칙(collation)이 bronze_cate_info와 같아야 하므로 문자셋을 지정하지 않고 DB 기본값을 따른다
CREATE TABLE `gold_faq_category` (
  `q_type` int(11) NOT NULL COMMENT '카테고리 코드 (100000부터)',
  `name` varchar(100) NOT NULL COMMENT '화면 표시명 (앞뒤 공백 제거, 연속 공백은 하나로)',
  `name_key` varchar(100) NOT NULL COMMENT '중복 확인용 이름 (공백 모두 제거)',
  `created_by` bigint(20) DEFAULT NULL COMMENT '만든 사용자 ID (탈퇴 시 NULL)',
  `created_at` datetime(6) NOT NULL DEFAULT current_timestamp(6),
  PRIMARY KEY (`q_type`),
  UNIQUE KEY `uq_faq_category_name_key` (`name_key`),
  KEY `fk_faq_category_created_by` (`created_by`),
  CONSTRAINT `fk_faq_category_created_by` FOREIGN KEY (`created_by`) REFERENCES `gold_user` (`user_id`)
) ENGINE=InnoDB COMMENT='BE에서 만든 FAQ 카테고리';
