import { request } from "./client.js";

function toQueryString(params) {
  const usp = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") usp.set(key, value);
  });
  const qs = usp.toString();
  return qs ? `?${qs}` : "";
}

export const auditApi = {
  // GET /api/v1/audit-logs — 관리자 전용, 최신순.
  //
  // 파라미터를 주지 않으면 서버가 "주요 작업"만 돌려준다
  // (AUTH·ANALYSIS·FAQ·EXPORT + 401·403으로 막힌 요청).
  // 자동 요청(AUTO: 진행 상태 확인, SSE, AI 콜백, 토큰 재발급)은 기록은 남지만 응답에서 항상 빠진다.
  //
  // includeReads=true 면 일반 조회(READ)까지 포함한다.
  // category 로 작업 종류를 거른다: AUTH / ANALYSIS / FAQ / EXPORT / READ.
  // from·to 는 yyyy-MM-dd(끝 날짜 포함), email·path 는 일부만 맞아도 찾고, result 는 success | fail.
  //
  // 파라미터·응답 칸 이름은 BE PR에서 확정 예정이라 바뀔 수 있다(2026-10-27 기준 미확정).
  getLogs: ({ from, to, email, category, method, path, result, includeReads, page = 0, size = 50 } = {}) =>
    request("GET", `/api/v1/audit-logs${toQueryString({
      from, to, email, category, method, path, result,
      includeReads: includeReads ? "true" : undefined,
      page, size,
    })}`),
};
