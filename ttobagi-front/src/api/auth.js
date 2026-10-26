import { request } from "./client.js";

export const authApi = {
  // POST /api/v1/auth/login
  login: (email, password) => request("POST", "/api/v1/auth/login", { body: { email, password }, skipAuthRetry: true }),

  // POST /api/v1/auth/signup — adminCode를 함께 보내면 즉시 ADMIN으로 가입됨 (SignupRequest엔 name 필드 없음)
  // POST /api/v1/auth/signup/code — 가입용 인증 코드 메일 발송.
  // 이미 가입된 이메일이면 409, 60초 안에 다시 요청하면 429.
  signupCode: (email) =>
    request("POST", "/api/v1/auth/signup/code", { body: { email }, skipAuthRetry: true }),

  // code는 백엔드 SignupRequest에서 @NotBlank 필수다.
  // 코드 불일치·만료 400, 이미 가입된 이메일 409, 5회 초과 429.
  signup: (email, password, code, adminCode) =>
    request("POST", "/api/v1/auth/signup", { body: { email, password, code, adminCode }, skipAuthRetry: true }),

  // POST /api/v1/auth/logout
  logout: () => request("POST", "/api/v1/auth/logout", { skipAuthRetry: true }),

  // POST /api/v1/auth/password/reset — 6자리 인증 코드 메일 발송 요청 (30분 유효)
  resetPassword: (email) => request("POST", "/api/v1/auth/password/reset", { body: { email }, skipAuthRetry: true }),

  // POST /api/v1/auth/password/reset/confirm — 이메일 + 6자리 코드 + 새 비밀번호로 실제 반영
  // token은 대소문자를 구분하므로 호출부에서 case를 변환하지 말고 공백만 잘라서 넘겨야 한다.
  resetPasswordConfirm: (email, token, newPassword) =>
    request("POST", "/api/v1/auth/password/reset/confirm", {
      body: { email, token, newPassword },
      skipAuthRetry: true,
    }),

  // GET /api/v1/auth/me
  getMe: () => request("GET", "/api/v1/auth/me"),

  // PATCH /api/v1/auth/me
  updateMe: (body) => request("PATCH", "/api/v1/auth/me", { body }),

  // DELETE /api/v1/auth/quit
  quit: () => request("DELETE", "/api/v1/auth/quit"),

  // POST /api/v1/auth/reissue
  reissue: (refreshToken) => request("POST", "/api/v1/auth/reissue", { body: { refreshToken }, skipAuthRetry: true }),

  // PATCH /api/v1/auth/promote
  promote: (email, adminCode) => request("PATCH", "/api/v1/auth/promote", { body: { email, adminCode } }),
};
