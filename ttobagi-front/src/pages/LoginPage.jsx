import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { authApi } from "../api/auth.js";
import { useAuth } from "../hooks/useAuth.js";
import { useToast } from "../hooks/useToast.js";
import { useIsMobile } from "../hooks/useIsMobile.js";
import Icon from "../components/Icon.jsx";

export default function LoginPage() {
  const isMobile = useIsMobile();
  const [email, setEmail]       = useState("");
  const [pw, setPw]             = useState("");
  const [remember, setRemember] = useState(false);
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState("");
  // 인라인 스타일에는 :focus를 쓸 수 없어 어느 칸이 눌렸는지 직접 들고 있는다
  const [focused, setFocused]   = useState(null);
  const { login } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();

  const submit = async () => {
    if (!email || !pw) { setError("이메일과 비밀번호를 입력해주세요."); return; }
    setLoading(true);
    setError("");
    try {
      const res = await authApi.login(email, pw);
      await login(res.data);
      toast.success("로그인되었습니다.");
      navigate("/dashboard", { replace: true });
    } catch (err) {
      setError(err.message || "이메일 또는 비밀번호가 올바르지 않습니다.");
    } finally {
      setLoading(false);
    }
  };

  const field = (key, { type, placeholder, icon, value, onChange }) => (
    <div style={{ ...S.inputWrap, ...(focused === key ? S.inputWrapOn : {}) }}>
      <span style={{ ...S.inputIcon, ...(focused === key ? { color: "#0D3B7A" } : {}) }}>
        <Icon name={icon} size={17} />
      </span>
      <input style={S.input} type={type} placeholder={placeholder}
        value={value} onChange={onChange}
        onFocus={() => setFocused(key)} onBlur={() => setFocused(null)}
        onKeyDown={(e) => e.key === "Enter" && submit()} />
    </div>
  );

  return (
    <div style={{ ...S.page, ...(isMobile ? S.pageMobile : {}) }}>
      {/* 흩어진 도형 대신 넓게 번지는 빛 두 개로 깊이만 준다 */}
      <div style={S.glowTop} />
      <div style={S.glowBottom} />

      <div style={{ ...S.card, ...(isMobile ? S.cardMobile : {}) }}>
        {/* 왼쪽은 소개용이라 좁은 화면에서는 접고 폼에 폭을 넘긴다 */}
        {!isMobile && (
          <div style={S.cardLeft}>
            <div style={S.brandRow}>
              <span style={S.brandMark}><Icon name="train" size={18} /></span>
              <span style={S.brandName}>또타24</span>
            </div>

            <div>
              <h1 style={S.leftTitle}>챗봇 학습데이터를<br />스스로 최신으로</h1>
              <p style={S.leftSub}>
                미답변 대화를 AI가 분석해 FAQ 후보를 제안하고,
                담당자가 승인한 내용만 반영합니다.
              </p>
            </div>

            <div style={S.leftFoot}>서울교통공사 · 정보운영센터</div>
          </div>
        )}

        <div style={{ ...S.cardRight, ...(isMobile ? S.cardRightMobile : {}) }}>
          {isMobile && (
            <div style={S.logoWrap}><Icon name="train" size={22} /></div>
          )}

          <h2 style={S.welcome}>반갑습니다</h2>
          <p style={S.desc}>운영자 계정으로 로그인하세요.</p>

          <div style={S.fields}>
            {field("email", {
              type: "email", placeholder: "이메일 주소", icon: "mail",
              value: email, onChange: (e) => setEmail(e.target.value),
            })}
            {field("pw", {
              type: "password", placeholder: "비밀번호", icon: "lock",
              value: pw, onChange: (e) => setPw(e.target.value),
            })}
          </div>

          <div style={S.row}>
            <label style={S.rememberLabel}>
              <input type="checkbox" checked={remember}
                onChange={(e) => setRemember(e.target.checked)}
                style={{ marginRight: 7, accentColor: "#0D3B7A", width: 14, height: 14 }} />
              로그인 상태 유지
            </label>
            <Link to="/reset" style={S.link}>비밀번호 찾기</Link>
          </div>

          {error && <div style={S.error}>{error}</div>}

          <button style={{ ...S.btn, ...(loading ? S.btnLoading : {}) }}
            onClick={submit} disabled={loading}>
            {loading ? "로그인 중..." : "로그인"}
          </button>

          <div style={S.divider}>
            <span style={S.dividerLine} />
            <span style={S.dividerText}>또는</span>
            <span style={S.dividerLine} />
          </div>

          <div style={S.signupRow}>
            <span style={{ color: "#64748B" }}>계정이 없으신가요?</span>
            <Link to="/signup" style={S.link}>회원가입</Link>
          </div>
        </div>
      </div>
    </div>
  );
}

const NAVY = "#0D3B7A";

const S = {
  // 밝은 파랑 그라데이션은 눈이 피로해, 짙은 남색에 빛만 얹는다
  page: {
    minHeight: "100vh",
    background: "#081A33",
    display: "flex", alignItems: "center", justifyContent: "center",
    padding: 24, position: "relative", overflow: "hidden",
  },
  pageMobile: { padding: 16 },

  glowTop: {
    position: "absolute", top: "-28%", left: "-10%", width: 760, height: 760,
    background: "radial-gradient(circle, rgba(37,99,235,.38) 0%, rgba(37,99,235,0) 68%)",
    pointerEvents: "none",
  },
  glowBottom: {
    position: "absolute", bottom: "-34%", right: "-14%", width: 820, height: 820,
    background: "radial-gradient(circle, rgba(14,165,233,.26) 0%, rgba(14,165,233,0) 68%)",
    pointerEvents: "none",
  },

  card: {
    display: "flex", width: 880, minHeight: 540,
    borderRadius: 20, overflow: "hidden", position: "relative", zIndex: 10,
    background: "#fff",
    boxShadow: "0 32px 80px -24px rgba(2,8,23,.55)",
  },
  cardMobile: { width: "100%", maxWidth: 420, minHeight: 0, borderRadius: 18 },

  // ── 왼쪽: 소개 ──
  cardLeft: {
    width: 360, flexShrink: 0,
    background: `linear-gradient(170deg, ${NAVY} 0%, #0A2550 100%)`,
    padding: "40px 38px",
    display: "flex", flexDirection: "column", justifyContent: "space-between",
  },
  brandRow:  { display: "flex", alignItems: "center", gap: 10 },
  brandMark: {
    display: "flex", alignItems: "center", justifyContent: "center",
    width: 32, height: 32, borderRadius: 9,
    background: "rgba(255,255,255,.14)", color: "#fff",
  },
  brandName: { fontSize: 15, fontWeight: 700, color: "#fff", letterSpacing: "-.01em" },

  leftTitle: {
    fontSize: 27, fontWeight: 700, color: "#fff",
    lineHeight: 1.42, letterSpacing: "-.035em", margin: "0 0 14px",
  },
  leftSub: {
    fontSize: 13, color: "rgba(255,255,255,.62)", lineHeight: 1.85,
    margin: 0, wordBreak: "keep-all",
  },
  leftFoot: { fontSize: 11, color: "rgba(255,255,255,.38)", letterSpacing: ".01em" },

  // ── 오른쪽: 폼 ──
  cardRight: {
    flex: 1, background: "#fff", padding: "48px 44px",
    display: "flex", flexDirection: "column", justifyContent: "center",
  },
  cardRightMobile: { padding: "36px 24px" },

  logoWrap: {
    width: 44, height: 44, borderRadius: 12,
    background: "#EEF2F7", color: NAVY,
    display: "flex", alignItems: "center", justifyContent: "center",
    marginBottom: 18,
  },

  welcome: { fontSize: 22, fontWeight: 700, color: "#0F172A", letterSpacing: "-.03em", margin: "0 0 6px" },
  desc:    { fontSize: 13, color: "#94A3B8", margin: "0 0 28px" },

  fields: { display: "flex", flexDirection: "column", gap: 10, marginBottom: 16 },

  inputWrap: {
    display: "flex", alignItems: "center",
    background: "#F8FAFC",
    border: "1.5px solid #E2E8F0",
    borderRadius: 12, padding: "0 14px",
    transition: "border-color .15s, box-shadow .15s, background .15s",
  },
  // 눌린 칸을 분명히 보여 준다
  inputWrapOn: {
    background: "#fff",
    borderColor: NAVY,
    boxShadow: `0 0 0 3px rgba(13,59,122,.1)`,
  },
  inputIcon: { display: "flex", alignItems: "center", marginRight: 10, color: "#94A3B8", transition: "color .15s" },
  input: {
    flex: 1, minWidth: 0, height: 48, border: "none", outline: "none",
    fontSize: 14, fontFamily: "inherit", background: "transparent", color: "#0F172A",
  },

  row: { display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10, marginBottom: 20 },
  rememberLabel: { display: "flex", alignItems: "center", fontSize: 12.5, color: "#64748B", cursor: "pointer" },
  link: { fontSize: 12.5, color: NAVY, cursor: "pointer", fontWeight: 600, textDecoration: "none" },
  error: {
    fontSize: 12.5, color: "#BE123C", background: "#FFF1F2", border: "1px solid #FECDD3",
    borderRadius: 10, padding: "9px 12px", marginBottom: 14, lineHeight: 1.5,
  },

  btn: {
    width: "100%", height: 48,
    background: NAVY, color: "#fff", border: "none", borderRadius: 12,
    fontSize: 14.5, fontWeight: 600, letterSpacing: "-.01em",
    cursor: "pointer", fontFamily: "inherit",
    boxShadow: "0 8px 20px -8px rgba(13,59,122,.6)",
    transition: "opacity .15s",
  },
  btnLoading: { opacity: .65, cursor: "default" },

  divider:     { display: "flex", alignItems: "center", gap: 12, margin: "22px 0" },
  dividerLine: { flex: 1, height: 1, background: "#E2E8F0" },
  dividerText: { fontSize: 11.5, color: "#94A3B8" },

  signupRow: { display: "flex", justifyContent: "center", alignItems: "center", gap: 6, fontSize: 13 },
};
