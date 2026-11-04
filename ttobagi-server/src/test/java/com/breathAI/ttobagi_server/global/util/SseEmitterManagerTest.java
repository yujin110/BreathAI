package com.breathAI.ttobagi_server.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseEmitterManagerTest {

    @Test
    @DisplayName("SSE 연결은 오래 걸리는 분석을 끝까지 받도록 8시간 유지된다")
    void emitterLastsEightHours() {
        SseEmitter emitter = new SseEmitterManager().create(1L);

        assertThat(emitter.getTimeout()).isEqualTo(Duration.ofHours(8).toMillis());
    }

    @Test
    @DisplayName("취소 상태를 보내면 연결을 닫고, 진행 상태는 연결을 유지한다")
    void canceledClosesConnection() {
        SseEmitterManager manager = new SseEmitterManager();
        SseEmitter running = manager.create(1L);
        SseEmitter canceled = manager.create(2L);

        manager.send(1L, "ANALYZING", "분석 중");
        manager.send(2L, "CANCELED", "분석이 취소되었습니다.");

        assertThatCode(() -> running.send("다음 상태")).doesNotThrowAnyException();
        assertThatThrownBy(() -> canceled.send("다음 상태")).isInstanceOf(IllegalStateException.class);
    }
}
