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

  // GET /api/v1/faq/{faqId}
  getOne: (faqId) => request("GET", `/api/v1/faq/${faqId}`),

  // GET /api/v1/faq/recommendations/{analysisId}
  getCandidates: (analysisId) => request("GET", `/api/v1/faq/recommendations/${analysisId}`),

  // POST /api/v1/faq/apply — ADMIN 전용
  apply: (body) => request("POST", "/api/v1/faq/apply", { body }),

  // PATCH /api/v1/faq/{faqId} — ADMIN 전용
  // body: { standardQuestion, answer, keywords, qType?, editReason? }
  update: (faqId, body) => request("PATCH", `/api/v1/faq/${faqId}`, { body }),

  // DELETE /api/v1/faq/{faqId} — ADMIN 전용 (비활성 처리)
  remove: (faqId) => request("DELETE", `/api/v1/faq/${faqId}`),

  // GET /api/v1/faq/history?page=0&size=10&editType=MANUAL
  // FAQ 단건이 아니라 전역 변경 이력이다. 서버가 faqId 필터를 받지 않으므로
  // 특정 FAQ의 이력만 뽑아낼 수는 없고, 목록의 각 행이 faqId를 들고 있다.
  // faqId를 주면 그 FAQ의 수정 이력만, 없으면 전체 이력
  getHistory: ({ page = 0, size = 10, editType, faqId } = {}) =>
    request("GET", `/api/v1/faq/history${toQueryString({ page, size, editType, faqId })}`),

  // GET /api/v1/faq/versions?page=0&size=10
  getVersions: (page = 0, size = 10) => request("GET", `/api/v1/faq/versions?page=${page}&size=${size}`),

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
