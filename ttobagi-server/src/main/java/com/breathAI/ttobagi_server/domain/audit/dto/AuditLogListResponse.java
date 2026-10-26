package com.breathAI.ttobagi_server.domain.audit.dto;

import com.breathAI.ttobagi_server.domain.audit.entity.AuditCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
// 감사로그 목록 응답, 최신순 페이징
public class AuditLogListResponse {
    private long totalCount;
    private int totalPages;
    private int currentPage;
    private int size;
    private List<LogItem> logs;

    @Getter
    @Builder
    public static class LogItem {
        private Long logId;

        @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'",
            timezone = "UTC"
        )
        private LocalDateTime createdAt;

        // 로그인하지 않은 요청(가입·로그인 등)은 요청에 적힌 이메일, AI 콜백 등은 null
        private String email;
        private String method;
        private String path;
        private String query;
        private Integer status;
        private Boolean success;
        private String ip;
        private Long durationMs;

        // 화면용 작업 이름·분류·대상과 결과 (예: "FAQ 수정", FAQ, "FAQ #123", "권한 없음")
        private String action;
        private AuditCategory category;
        private String target;
        private String resultLabel;
    }
}
