import { request, requestFile } from "./client.js";

function toQueryString(params) {
  const usp = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") usp.set(key, value);
  });
  const qs = usp.toString();
  return qs ? `?${qs}` : "";
}

export const faqApi = {
  // GET /api/v1/faq?versionId=&page=0&size=10&category=&keyword=
  // versionId를 주면 그 버전 시점의 FAQ, 생략하면 현재 운영 FAQ를 조회한다.
  // category는 코드(qType)가 아니라 카테고리명 문자열이며, 서버가 마스터에서 qType으로 변환한다.
  getList: ({ page = 0, size = 10, versionId, category, keyword } = {}) =>
    request("GET", `/api/v1/faq${toQueryString({ versionId, page, size, category, keyword })}`),

  // POST /api/v1/faq — 관리자 전용 FAQ 직접 등록.
  // standardQuestion·answer 는 필수(비면 400), keywords·qType 은 선택.
  // 응답은 FAQ 상세와 같은 형식이고 변경 이력에 CREATE 로 남는다.
  create: (body) => request("POST", "/api/v1/faq", { body }),

  // GET /api/v1/faq/{faqId}
  getOne: (faqId) => request("GET", `/api/v1/faq/${faqId}`),

  // GET /api/v1/faq/recommendations/{analysisId}
  getCandidates: (analysisId) => request("GET", `/api/v1/faq/recommendations/${analysisId}`),

  // PATCH /api/v1/faq/recommendations/{candidateId}/reject — 관리자 전용
  // 본문 { reason }은 선택이라 없이 보내도 된다. 성공 시 data 없음.
  // 대기(PENDING)가 아닌 후보는 409, 없는 후보는 404.
  rejectCandidate: (candidateId, reason) =>
    request("PATCH", `/api/v1/faq/recommendations/${candidateId}/reject`,
      { body: reason ? { reason } : {} }),

  // PATCH /api/v1/faq/recommendations/{candidateId} — 관리자 전용, 저장만 하고 승인은 아니다.
  // standardQuestion·answerDraft는 필수(비면 400), keywords·qType은 생략하면 기존 값 유지.
  // 응답은 후보 목록의 항목 하나와 같은 형식이라 그 카드만 교체하면 된다. 대기 후보만 수정 가능(409).
  updateCandidate: (candidateId, body) =>
    request("PATCH", `/api/v1/faq/recommendations/${candidateId}`, { body }),

  // POST /api/v1/faq/apply — ADMIN 전용
  apply: (body) => request("POST", "/api/v1/faq/apply", { body }),

  // PATCH /api/v1/faq/{faqId} — ADMIN 전용
  // body: { standardQuestion, answer, keywords, qType?, editReason? }
  update: (faqId, body) => request("PATCH", `/api/v1/faq/${faqId}`, { body }),

  // DELETE /api/v1/faq/{faqId} — ADMIN 전용 (비활성 처리)
  remove: (faqId) => request("DELETE", `/api/v1/faq/${faqId}`),

  // GET /api/v1/faq/history?page=0&size=10&editType=MANUAL&faqId=
  // faqId를 주면 그 FAQ의 수정 이력만(삭제된 FAQ 포함), 생략하면 전체 변경 이력.
  // editType과 함께 쓸 수 있다.
  getHistory: ({ page = 0, size = 10, editType, faqId } = {}) =>
    request("GET", `/api/v1/faq/history${toQueryString({ page, size, editType, faqId })}`),

  // GET /api/v1/faq/versions?page=0&size=10
  getVersions: (page = 0, size = 10) => request("GET", `/api/v1/faq/versions?page=${page}&size=${size}`),

  // POST /api/v1/faq/versions — 관리자 전용.
  // 다운로드하지 않고 지금 상태를 버전으로 저장한다(versionType = MANUAL).
  createVersion: () => request("POST", "/api/v1/faq/versions"),

  // POST /api/v1/faq/versions/{versionId}/restore — 관리자 전용.
  // 그 버전 이후의 수정·등록·삭제와 후보 반영이 취소되고, 결과가 새 버전(RESTORE)으로 저장된다.
  // 바뀐 게 없으면 newVersionId 가 없다.
  restoreVersion: (versionId) =>
    request("POST", `/api/v1/faq/versions/${versionId}/restore`),

  // GET /api/v1/faq/versions/compare?baseId=&targetId=
  // targetId를 생략하면 기준 버전과 현재 운영 FAQ를 비교하고, 응답에 targetVersionId가 없다.
  compareVersions: (baseId, targetId) =>
    request("GET", `/api/v1/faq/versions/compare${toQueryString({ baseId, targetId })}`),

  // GET /api/v1/faq/versions/{versionId}
  getVersionDetail: (versionId) => request("GET", `/api/v1/faq/versions/${versionId}`),

  // GET /api/v1/faq/categories
  // 카테고리 필터 선택지. 응답은 { categories: [{ qType, name }] }.
  // 호출부(FaqPage)는 실패하면 문자열 입력으로 떨어진다.
  getCategories: () => request("GET", "/api/v1/faq/categories"),

  // GET /api/v1/faq/download?versionId=
  // ApiResponse JSON이 아니라 text/csv 본문으로 내려오므로 requestFile을 쓴다.
  // versionId를 생략하면 서버가 최신 내용으로 새 버전(VersionType.DOWNLOAD)을 만들고 그걸 내려준다.
  downloadCsv: (versionId) =>
    requestFile("GET", `/api/v1/faq/download${toQueryString({ versionId })}`, "faq.csv"),
};
