package com.breathAI.ttobagi_server.global.util;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// 분석 진행 상태 실시간 전송을 위한 SSE 연결 관리자
// 제약: 연결을 메모리에 보관하므로 다중 인스턴스 환경에서 알림 유실 가능
@Component
public class SseEmitterManager {

    // analysisId 기준 구독 중인 연결 목록 (같은 분석을 여러 명이 구독할 수 있다)
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    // AI 분석이 수십 분에서 몇 시간까지 걸리므로 한 번 연결로 끝까지 받을 수 있게 넉넉히 잡는다
    // 그래도 끊기면 클라이언트가 다시 연결하고, 연결 시 현재 상태를 바로 보낸다
    private static final long TIMEOUT_MILLIS = 8 * 60 * 60 * 1000L;

    // SSE 연결 생성 및 보관, 타임아웃 8시간
    public SseEmitter create(Long analysisId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        // 빈 목록 정리(remove)와 겹쳐도 연결이 사라지지 않도록 추가도 compute 안에서 한다
        emitters.compute(analysisId, (id, set) -> {
            Set<SseEmitter> target = (set != null) ? set : ConcurrentHashMap.newKeySet();
            target.add(emitter);
            return target;
        });
        emitter.onCompletion(() -> remove(analysisId, emitter));
        emitter.onTimeout(() -> remove(analysisId, emitter));
        emitter.onError(e -> remove(analysisId, emitter));
        return emitter;
    }

    // 프록시가 조용한 연결을 끊지 않도록 20초마다 빈 신호(SSE 주석)를 보낸다
    // nginx 기본 대기 시간(proxy_read_timeout 60초)보다 짧게 잡았다
    @Scheduled(fixedRate = 20_000)
    public void sendHeartbeat() {
        emitters.forEach((analysisId, set) -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                // 끊긴 연결은 목록에서 제거한다
                remove(analysisId, emitter);
            }
        }));
    }

    // 해당 분석을 구독 중인 모든 클라이언트에 상태 변경 전송
    public void send(Long analysisId, String status, String message) {
        Set<SseEmitter> set = emitters.get(analysisId);
        if (set == null) return;
        set.forEach(emitter -> send(emitter, analysisId, status, message));
    }

    // 지정한 연결에 상태 전송, 종료 상태면 연결을 닫는다
    public void send(SseEmitter emitter, Long analysisId, String status, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("{\"status\":\"" + status + "\",\"message\":\"" + message + "\"}"));
            if ("COMPLETED".equals(status) || "FAIL".equals(status) || "CANCELED".equals(status)) {
                emitter.complete();
            }
        } catch (Exception e) {
            remove(analysisId, emitter);
        }
    }

    // 해당 분석을 구독 중인 모든 클라이언트에 세부 진행 정보 전송 (progress 이벤트, 연결은 유지)
    public void sendProgress(Long analysisId, Object progress) {
        Set<SseEmitter> set = emitters.get(analysisId);
        if (set == null) return;
        set.forEach(emitter -> sendProgress(emitter, analysisId, progress));
    }

    // 지정한 연결에 세부 진행 정보 전송
    public void sendProgress(SseEmitter emitter, Long analysisId, Object progress) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(progress, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            remove(analysisId, emitter);
        }
    }

    // 연결 하나만 빼고, 목록이 비면 분석 항목도 지운다
    private void remove(Long analysisId, SseEmitter emitter) {
        emitters.computeIfPresent(analysisId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
