import { useState } from "react";
import { authApi } from "../api/auth.js";
import { reissueTokens, storeTokens } from "../api/client.js";
import { useAuth } from "../hooks/useAuth.js";
import { useToast } from "../hooks/useToast.js";

// 참고: 백엔드 /promote는 @AuthenticationPrincipal(현재 로그인한 사용자)만 승격시키고
// 요청 바디의 email은 사용하지 않는다 — 그래서 "내 계정 승격" UX로 구성한다.
//
// 승격은 DB의 role만 ADMIN으로 바꾸고, 들고 있던 액세스 토큰의 role 클레임은 USER 그대로다.
// JwtAuthenticationFilter가 토큰의 role로 권한을 판단하므로 화면만 관리자가 되고
// 업로드 같은 관리자 요청은 403으로 막힌다. 그래서 승격 직후 토큰을 새로 받는다.
// /auth/reissue는 리프레시 토큰으로 DB의 사용자를 다시 읽어 토큰을 만들기 때문에
// (AuthService.reissue → JwtUtil.generateToken) 새 토큰에는 ADMIN이 담긴다.
export default function AdminPromotePage() {
  const { user, setUser } = useAuth();
  const [adminCode, setAdminCode] = useState("");
  const [loading, setLoading]     = useState(false);
  const [error, setError]         = useState("");
  const toast = useToast();

  const submit = async () => {
    if (!adminCode) { setError("관리자 인증 코드를 입력해주세요."); return; }
    setLoading(true);
    setError("");
    try {
      const res = await authApi.promote(user?.email, adminCode);
      const data = res.data || {};

      // 백엔드가 응답에 새 토큰을 담아 주면 그대로 쓰고(fix/auth/promote/BE),
      // 아직 담기지 않는 동안에는 재발급으로 같은 결과를 만든다.
      const tokens = data.accessToken ? data : data.tokens;
      if (tokens?.accessToken) storeTokens(tokens);
      else await reissueTokens();

      // 새 토큰으로 내 정보를 다시 읽어, 화면의 권한과 토큰의 권한을 어긋나지 않게 맞춘다.
      const me = await authApi.getMe();
      setUser(me.data);
      setAdminCode("");
      toast.success("관리자 권한이 부여되었습니다.");
    } catch (err) {
      // 승격 자체는 DB에 반영됐는데 토큰만 못 받은 경우가 있어, 다시 로그인하면 풀린다는 것을 알린다.
      setError(err.message
        ? `${err.message} (권한이 적용되지 않으면 로그아웃 후 다시 로그인해주세요.)`
        : "권한 부여에 실패했습니다. 로그아웃 후 다시 로그인해주세요.");
    } finally {
      setLoading(false);
    }
  };

  if (user?.role === "ADMIN") {
    return (
      <div style={S.page}>
        <div style={S.card}>
          <div style={{ fontSize: 14, color: "#111827" }}>이미 관리자 권한을 보유하고 있습니다.</div>
        </div>
      </div>
    );
  }

  return (
    <div style={S.page}>
      <div style={S.card}>
        <div style={S.title}>관리자 권한 부여</div>
        <div style={S.desc}>현재 계정({user?.email})에 관리자 인증 코드를 입력하면 즉시 ADMIN 권한이 부여됩니다.</div>

        <input style={S.input} type="password" placeholder="관리자 인증 코드"
          value={adminCode} onChange={(e) => setAdminCode(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()} />

        {error && <div style={S.error}>{error}</div>}

        <button style={{ ...S.btn, opacity: loading ? 0.7 : 1 }} onClick={submit} disabled={loading}>
          {loading ? "처리 중..." : "권한 부여"}
        </button>
      </div>
    </div>
  );
}

const S = {
  page:  { padding: 28, display: "flex", justifyContent: "center" },
  card:  { background: "#fff", borderRadius: 12, boxShadow: "0 1px 3px rgba(0,0,0,.08)", border: "1px solid rgba(0,0,0,.06)", padding: 28, maxWidth: 420, width: "100%" },
  title: { fontSize: 18, fontWeight: 700, color: "#111827", marginBottom: 6 },
  desc:  { fontSize: 13, color: "#6B7280", marginBottom: 20, lineHeight: 1.6 },
  input: { width: "100%", height: 44, padding: "0 14px", border: "1.5px solid #D1D5DB", borderRadius: 8, fontSize: 13, fontFamily: "inherit", outline: "none", boxSizing: "border-box", marginBottom: 12 },
  error: { fontSize: 12, color: "#DC2626", marginBottom: 12 },
  btn:   { width: "100%", height: 46, background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
};
