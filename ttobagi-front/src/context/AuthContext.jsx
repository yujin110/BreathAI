import { useEffect, useState, useCallback } from "react";
import { authApi } from "../api/auth.js";
import { clearTokens, setAuthExpiredHandler } from "../api/client.js";
import { AuthContext } from "./auth-context.js";

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(() => Boolean(localStorage.getItem("token")));

  const logout = useCallback(() => {
    clearTokens();
    setUser(null);
  }, []);

  useEffect(() => {
    // 자동 재발급까지 실패하면(리프레시 토큰도 만료) 강제 로그아웃
    setAuthExpiredHandler(logout);
  }, [logout]);

  useEffect(() => {
    if (!localStorage.getItem("token")) return;
    // 새로고침 시에도 로그인 상태 유지 — 저장된 토큰으로 세션 복원 시도
    authApi.getMe()
      .then((res) => setUser(res.data))
      .catch(() => clearTokens())
      .finally(() => setLoading(false));
  }, []);

  // 다른 탭에서 로그인하거나 로그아웃하면 localStorage가 바뀌고 storage 이벤트가 이 탭으로 온다.
  //
  // 요청은 매번 localStorage에서 토큰을 새로 읽지만(client.js getStoredTokens) 화면의 user는
  // 위 effect가 한 번 채운 뒤 그대로다. 그래서 다른 탭에서 계정이 바뀌면 토큰만 새것이 되고
  // 화면은 예전 권한을 들고 있게 된다 — 관리자인데 업로드 칸이 잠겨 보이는 식이다.
  //
  // storage 이벤트는 값을 쓴 탭에는 오지 않고 다른 탭에만 온다(브라우저 규격).
  // 쓴 탭은 login()에서 이미 user를 갱신하므로, 둘을 합치면 모든 탭이 같은 권한을 본다.
  useEffect(() => {
    const onStorage = (e) => {
      if (e.key !== "token") return;
      // 다른 탭에서 로그아웃했다. 토큰이 없으니 이 탭의 요청도 어차피 401로 끊긴다.
      // 오류를 쌓게 두지 말고 바로 로그인 화면으로 보낸다.
      if (!e.newValue) { setUser(null); return; }
      // 토큰만 바뀐 것인지 계정까지 바뀐 것인지 알 수 없으므로 서버에 다시 물어 본다.
      authApi.getMe()
        .then((res) => setUser(res.data))
        .catch(() => { /* 실패하면 다음 요청의 401 처리에 맡긴다 */ });
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const login = useCallback(async (tokens) => {
    // 토큰을 먼저 저장해야 뒤이은 getMe() 요청에 Authorization 헤더가 실린다.
    // 순서가 바뀌면(토큰 저장 전에 getMe 호출) 인증 없이 요청이 나가
    // 백엔드가 "사용자를 찾을 수 없습니다"(404)를 반환한다.
    if (tokens?.accessToken) localStorage.setItem("token", tokens.accessToken);
    if (tokens?.refreshToken) localStorage.setItem("refreshToken", tokens.refreshToken);
    const res = await authApi.getMe();
    setUser(res.data);
    return res.data;
  }, []);

  return (
    <AuthContext.Provider value={{ user, setUser, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
