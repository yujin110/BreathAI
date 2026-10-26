import { request, getStoredTokens, BASE_URL } from "./client.js";

function toQueryString(params) {
  const usp = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") usp.set(key, value);
  });
  const qs = usp.toString();
  return qs ? `?${qs}` : "";
}

export const dashApi = {
  // GET /api/v1/dashboard/usage?year=2026&month=4
  // 월 단위 챗봇 사용량(UsageResponse: totalUsageCount, increaseRate, dailyUsage).
  // 대시보드는 미답변·오답변 현황을 보여 주므로 단순 사용량 집계는 쓰지 않는다.
  // 명세서에는 있는 엔드포인트라 호출부만 비워 두고 함수는 남겨 둔다.
  getUsage: (year, month) => request("GET", `/api/v1/dashboard/usage?year=${year}&month=${month}`),

  // POST /api/v1/dashboard/analyze/upload (업로드 + 분석 통합)
  // 기간은 백엔드가 LocalDate.parse()로 포맷터 없이 파싱하므로 ISO(yyyy-MM-dd)여야 하고,
  // 빈 문자열을 보내면 parse("")가 터져 500이 되므로 미입력 항목은 아예 append하지 않는다.
  // 군집화 설정(minClusterSize >= 2, minSamples >= 1)은 백엔드가 그대로 AI로 전달한다.
  // 범위를 벗어나면 백엔드가 400(INVALID_INPUT_VALUE)을 낸다. 빈 값은 보내지 않는다.
  uploadAndAnalyze: (file, opts = {}) => {
    const { periodStartDate, periodEndDate, isMaskingEnabled, isTranslationEnabled, clustering } = opts;

    const formData = new FormData();
    formData.append("file", file);
    if (periodStartDate) formData.append("periodStartDate", periodStartDate);
    if (periodEndDate) formData.append("periodEndDate", periodEndDate);
    if (isMaskingEnabled !== undefined) formData.append("isMaskingEnabled", String(isMaskingEnabled));
    if (isTranslationEnabled !== undefined) formData.append("isTranslationEnabled", String(isTranslationEnabled));
    Object.entries(clustering || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") formData.append(key, String(value));
    });

    return request("POST", "/api/v1/dashboard/analyze/upload", { body: formData, isFormData: true });
  },

  // GET /api/v1/dashboard/analyze/{analysisId}
  getAnalysisStatus: (analysisId) => request("GET", `/api/v1/dashboard/analyze/${analysisId}`),

  // GET /api/v1/dashboard/analyze/{analysisId}/result
  getFileAnalyzeResult: (analysisId) => request("GET", `/api/v1/dashboard/analyze/${analysisId}/result`),

  // GET /api/v1/dashboard/analyze/result?startDate=yyyy-MM-dd&endDate=yyyy-MM-dd
  // 날짜는 @DateTimeFormat(iso = ISO.DATE)로 파싱되므로 ISO 형식(2026-04-01)이어야 한다.
  // 미지정(기간 없음) 시 최신 분석을 반환하도록 백엔드가 처리하므로, 빈 값은 아예 쿼리에서 뺀다.
  // scope 기본값은 mine(내 이력에 남은 완료 분석). all이면 완료된 분석 전체.
  getAnalyzeResult: ({ startDate, endDate, scope } = {}) =>
    request("GET", `/api/v1/dashboard/analyze/result${toQueryString({ startDate, endDate, scope })}`),

  // GET /api/v1/dashboard/analyze/history?page=&size=&scope=mine|all&status=
  // scope 기본값은 mine(내 이력). all이면 모든 사람의 이력을 본다.
  // status를 주면 그 상태만 (예: COMPLETED). 토큰으로 사용자를 식별하므로 별도 파라미터는 없다.
  getAnalysisHistory: ({ page = 0, size = 10, scope, status } = {}) =>
    request("GET", `/api/v1/dashboard/analyze/history${toQueryString({ page, size, scope, status })}`),

  // DELETE /api/v1/dashboard/analyze/history/{analysisId}
  // 내 이력에서만 지운다. 분석 결과·후보·반영된 FAQ는 그대로 남고, 남의 줄은 404가 된다.
  deleteAnalysisHistory: (analysisId) =>
    request("DELETE", `/api/v1/dashboard/analyze/history/${analysisId}`),

  // GET /api/v1/dashboard/analyze/stream/{analysisId} (SSE)
  // 이 백엔드의 JwtAuthenticationFilter는 Authorization 헤더만 읽고 쿼리 파라미터 토큰은
  // 전혀 확인하지 않는다. EventSource는 커스텀 헤더를 보낼 수 없어 이 엔드포인트를 인증할
  // 방법이 없으므로(항상 익명 → 403), fetch + ReadableStream으로 SSE를 직접 파싱해
  // 실제 Authorization 헤더를 싣는다. 백엔드는 SseEmitter.event().name("status")로
  // 이름 있는 이벤트만 보낸다.
  // onEnd: 종료 상태(COMPLETED/FAIL)를 받지 못한 채 스트림이 끊겼을 때 호출된다.
  // 백엔드 SseEmitterManager는 COMPLETED/FAIL에서 emitter.complete()로 연결을 닫지만,
  // 1시간 타임아웃이나 네트워크 단절로도 똑같이 "정상 종료"로 보인다. 그 경우 예외가
  // 발생하지 않아 onError도 안 타므로, 호출부가 폴링으로 넘어갈 수 있게 따로 알린다.
  streamAnalysisStatus: (analysisId, onMessage, onError, onEnd) => {
    const { accessToken } = getStoredTokens();
    const controller = new AbortController();
    let closedByServer = false; // 종료 상태를 받았는지

    (async () => {
      try {
        const res = await fetch(`${BASE_URL}/api/v1/dashboard/analyze/stream/${analysisId}`, {
          headers: { Authorization: `Bearer ${accessToken || ""}`, Accept: "text/event-stream" },
          signal: controller.signal,
        });
        if (!res.ok || !res.body) throw new Error("SSE 연결에 실패했습니다.");

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          let sepIndex;
          while ((sepIndex = buffer.indexOf("\n\n")) !== -1) {
            const rawEvent = buffer.slice(0, sepIndex);
            buffer = buffer.slice(sepIndex + 2);

            let eventName = "message";
            const dataLines = [];
            for (const line of rawEvent.split("\n")) {
              if (line.startsWith("event:")) eventName = line.slice(6).trim();
              else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
            }
            if (eventName === "status" && dataLines.length) {
              const raw = dataLines.join("\n");
              // 종료 상태를 미리 가려내 스트림이 끊겼을 때의 처리를 구분한다
              try {
                const status = JSON.parse(raw).status;
                if (status === "COMPLETED" || status === "FAIL") closedByServer = true;
              } catch {
                /* 파싱 실패는 onMessage에서 처리 */
              }
              onMessage(raw);
            }
          }
        }
        // 종료 상태 없이 스트림이 끝났으면 폴링으로 이어받아야 한다
        if (!closedByServer) onEnd?.();
      } catch (err) {
        if (err.name !== "AbortError") onError?.();
      }
    })();

    // EventSource와 동일하게 close()로 정리할 수 있게 유지
    return { close: () => controller.abort() };
  },
};
