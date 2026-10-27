const BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

export class ApiError extends Error {
  constructor(message, status, data) {
    super(message || "요청 처리 중 오류가 발생했습니다.");
    this.name = "ApiError";
    this.status = status;
    this.data = data;
  }
}

export function getStoredTokens() {
  return {
    accessToken: localStorage.getItem("token"),
    refreshToken: localStorage.getItem("refreshToken"),
  };
}

export function storeTokens({ accessToken, refreshToken }) {
  if (accessToken) localStorage.setItem("token", accessToken);
  if (refreshToken) localStorage.setItem("refreshToken", refreshToken);
}

export function clearTokens() {
  localStorage.removeItem("token");
  localStorage.removeItem("refreshToken");
}

// AuthContext가 mount 시 등록 — 재발급까지 실패하면 로그인 화면으로 보낸다.
let onAuthExpired = null;
export function setAuthExpiredHandler(fn) {
  onAuthExpired = fn;
}

// 백엔드가 인증 실패를 401, 권한 부족을 403으로 명확히 구분해 응답한다
// (SecurityConfig의 authenticationEntryPoint / accessDeniedHandler).
// 그래도 만료 직전에 미리 갱신해두면 요청이 한 번 실패했다가 재시도되는 왕복을 줄일 수 있어
// 토큰의 exp 클레임을 읽어 선제 재발급한다. 놓친 경우는 401 응답에서 1회 재시도로 처리한다.
function decodeJwtExpMs(token) {
  try {
    const payload = token.split(".")[1];
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const json = JSON.parse(atob(base64));
    return typeof json.exp === "number" ? json.exp * 1000 : null;
  } catch {
    return null;
  }
}

function isAccessTokenExpiringSoon() {
  const { accessToken } = getStoredTokens();
  if (!accessToken) return true;
  const expMs = decodeJwtExpMs(accessToken);
  if (expMs === null) return false; // exp를 못 읽으면 판단 보류 (요청 그대로 시도)
  return Date.now() >= expMs - 10_000; // 만료 10초 전부터 미리 갱신
}

// HTTP 상태 코드별 기본 메시지. 백엔드가 JSON 바디를 못 주는 상황(프록시 오류 등)에서만 쓴다.
const FALLBACK_MESSAGE = {
  401: "로그인이 필요합니다.",
  403: "권한이 없습니다.",
  404: "요청한 정보를 찾을 수 없습니다.",
  405: "지원하지 않는 요청 방식입니다.",
  409: "이미 처리된 요청입니다.",
  415: "지원하지 않는 형식입니다.",
  413: "파일이 너무 큽니다.",
  500: "서버 내부 오류가 발생했습니다.",
  502: "서버에 연결할 수 없습니다.",
  503: "서버가 일시적으로 응답할 수 없습니다.",
};

// 응답 본문과 HTTP 상태를 함께 돌려준다.
// 판단 기준은 HTTP 상태(res.status)이고, 본문의 status 필드는 참고값으로만 쓴다.
// 403 응답은 백엔드가 {"message": ...}만 내려주어 success/status가 없기 때문이다.
async function rawRequest(method, path, body, accessToken, isFormData) {
  const headers = {};
  if (accessToken) headers["Authorization"] = `Bearer ${accessToken}`;
  if (!isFormData) headers["Content-Type"] = "application/json";

  let res;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: isFormData ? body : body ? JSON.stringify(body) : null,
    });
  } catch {
    throw new ApiError("서버에 연결할 수 없습니다.", 0, null);
  }

  let json = null;
  try {
    json = await res.json();
  } catch {
    // 본문이 비었거나 JSON이 아닌 경우 — 상태 코드만으로 처리한다
  }
  return { res, json };
}

// 실패 응답을 ApiError로 바꾼다. 상태는 항상 HTTP 상태를 쓴다.
function toApiError(res, json) {
  const message = json?.message || FALLBACK_MESSAGE[res.status] || "요청 처리 중 오류가 발생했습니다.";
  return new ApiError(message, res.status, json?.data ?? null);
}

// 동시에 여러 401이 터져도 재발급 요청은 한 번만 나가도록 공유
let refreshPromise = null;
export async function reissueTokens() {
  const { refreshToken } = getStoredTokens();
  if (!refreshToken) throw new ApiError("로그인이 필요합니다.", 401, null);

  if (!refreshPromise) {
    refreshPromise = rawRequest("POST", "/api/v1/auth/reissue", { refreshToken }, null, false)
      .then(({ res, json }) => {
        if (!res.ok || json?.success !== true) throw toApiError(res, json);
        storeTokens(json.data);
        return json.data;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

/**
 * @param {string} method
 * @param {string} path
 * @param {{body?: any, isFormData?: boolean, skipAuthRetry?: boolean}} [opts]
 * skipAuthRetry: 토큰 만료/재발급 로직을 아예 건너뜀 (로그인/회원가입/재발급 자체 호출용)
 */
async function ensureFreshToken() {
  // 요청 전에 만료 임박 여부를 먼저 확인해 선제적으로 재발급 (위 설명 참고)
  if (!isAccessTokenExpiringSoon() || !getStoredTokens().refreshToken) return;
  try {
    await reissueTokens();
  } catch {
    clearTokens();
    onAuthExpired?.();
    throw new ApiError("로그인이 만료되었습니다. 다시 로그인해주세요.", 401, null);
  }
}

export async function request(method, path, opts = {}) {
  const { body, isFormData, skipAuthRetry } = opts;

  if (!skipAuthRetry) await ensureFreshToken();

  const { accessToken } = getStoredTokens();
  let { res, json } = await rawRequest(method, path, body, accessToken, isFormData);

  // 401은 인증 실패(토큰 없음·만료·위조)다. 재발급을 1회 시도하고, 그래도 실패하면 로그아웃.
  // 403(권한 부족)은 다시 로그인해도 해결되지 않으므로 토큰을 지우지 않는다.
  if (res.status === 401 && !skipAuthRetry) {
    try {
      const { accessToken: newToken } = await reissueTokens();
      ({ res, json } = await rawRequest(method, path, body, newToken, isFormData));
    } catch {
      clearTokens();
      onAuthExpired?.();
      throw new ApiError("로그인이 만료되었습니다. 다시 로그인해주세요.", 401, null);
    }
    if (res.status === 401) {
      clearTokens();
      onAuthExpired?.();
      throw toApiError(res, json);
    }
  }

  if (!res.ok) throw toApiError(res, json);

  // 200인데 success:false인 경우까지 방어한다 (상태 코드와 본문이 어긋나는 응답)
  if (json?.success === false) throw toApiError(res, json);

  return json;
}

// Content-Disposition에서 파일명을 꺼낸다. 백엔드가 ASCII 파일명(faq_v3_20261011.csv)을
// 쓰고 있어 filename= 형태지만, 한글 파일명이 되면 filename*=UTF-8''... 로 오므로 둘 다 본다.
function parseFileName(disposition, fallback) {
  if (!disposition) return fallback;
  const utf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1]);
    } catch {
      /* 디코딩 실패 시 아래 filename= 로 폴백 */
    }
  }
  const plain = disposition.match(/filename="?([^";]+)"?/i);
  return plain ? plain[1] : fallback;
}

/**
 * 파일 응답 전용 요청. GET /api/v1/faq/download는 ApiResponse JSON이 아니라
 * text/csv 본문 + Content-Disposition 헤더로 내려오므로 res.json()을 쓸 수 없다.
 * 실패 응답은 JSON(ApiResponse)으로 오기 때문에 그때만 파싱해 ApiError로 바꾼다.
 * @returns {Promise<{blob: Blob, fileName: string}>}
 */
export async function requestFile(method, path, fallbackFileName = "download") {
  await ensureFreshToken();

  const { accessToken } = getStoredTokens();
  const headers = {};
  if (accessToken) headers["Authorization"] = `Bearer ${accessToken}`;

  let res;
  try {
    res = await fetch(`${BASE_URL}${path}`, { method, headers });
  } catch {
    throw new ApiError("서버에 연결할 수 없습니다.", 0, null);
  }

  if (!res.ok) {
    let json = null;
    try {
      json = await res.json();
    } catch {
      /* 에러 본문이 JSON이 아니면 상태 코드만으로 처리 */
    }
    // 401이면 토큰이 끊긴 것이므로 로그인 화면으로 보낸다
    if (res.status === 401) {
      clearTokens();
      onAuthExpired?.();
    }
    throw toApiError(res, json);
  }

  return {
    blob: await res.blob(),
    fileName: parseFileName(res.headers.get("Content-Disposition"), fallbackFileName),
  };
}

/**
 * 입력값 검증 실패(400)일 때 백엔드가 data에 필드별 메시지를 담아 보낸다.
 * 예: { "password": "비밀번호 형식이 올바르지 않습니다." }
 * 화면에서 한 줄로 이어 붙여 보여 줄 때 쓴다. 해당 형태가 아니면 빈 문자열.
 */
export function fieldErrors(err) {
  const data = err?.data;
  if (!data || typeof data !== "object" || Array.isArray(data)) return "";
  return Object.values(data).filter((v) => typeof v === "string").join(" · ");
}

// 받은 파일을 다운로드시킨다. a[download]를 쓰면 Content-Disposition의 파일명을 유지할 수 있다.
export function saveBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export { BASE_URL };
