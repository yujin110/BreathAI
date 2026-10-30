package com.breathAI.ttobagi_server.domain.faq.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 버전 생성 시점의 FAQ 사본
// 지난 버전의 목록 조회와 다운로드에 사용하며, 버전 생성 시 한 번에 적재된다
@Entity
@Getter
@Table(
    name = "gold_faq_version_item",
    comment = "버전별 FAQ 사본",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_version_item_faq", columnNames = {"version_id", "faq_id"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqVersionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_version_item_version_id"))
    private FaqVersion version;

    @Column(name = "faq_id", nullable = false, columnDefinition = "BIGINT NOT NULL COMMENT '원본 FAQ ID'")
    private Long faqId;

    @Column(name = "q_type", columnDefinition = "INT COMMENT '카테고리 코드'")
    private Integer qType;

    @Column(name = "category", length = 100, columnDefinition = "VARCHAR(100) COMMENT '일반상담 카테고리'")
    private String category;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT NOT NULL COMMENT '질문'")
    private String question;

    @Column(name = "answer", nullable = false, columnDefinition = "LONGTEXT NOT NULL COMMENT '답변'")
    private String answer;

    @Column(name = "keywords", columnDefinition = "JSON COMMENT '키워드 배열'")
    private String keywords;

    // V12 이전에 만든 버전은 NULL (그때는 유사어가 없었다)
    @Column(name = "synonyms", columnDefinition = "JSON COMMENT '유사어 배열'")
    private String synonyms;

    @Column(name = "faq_created_at", nullable = false,
            columnDefinition = "DATETIME(6) NOT NULL COMMENT '원본 FAQ 등록 시각'")
    private LocalDateTime faqCreatedAt;
}
