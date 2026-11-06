package com.breathAI.ttobagi_server.global.config;

import com.breathAI.ttobagi_server.TtobagiServerApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 기본 분석 모드 설정값 검사, 오타가 난 채로 서버가 뜨지 않아야 한다
class AnalysisModeConfigTest {

    @Test
    @DisplayName("기본 분석 모드 설정이 잘못되면 서버가 시작하지 않는다")
    void invalidDefaultModeStopsStartup() {
        // properties()는 application.properties보다 우선순위가 낮아 명령줄 인자로 넘긴다
        assertThatThrownBy(() -> {
            try (var context = new SpringApplicationBuilder(TtobagiServerApplication.class)
                    .profiles("test")
                    .run("--ai.analysis.default-mode=IMPROVMENT", "--server.port=0")) {
                // 뜨면 실패이며, 띄운 서버는 닫는다
            }
        }).hasStackTraceContaining("IMPROVMENT");
    }
}
