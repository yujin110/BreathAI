import { useEffect, useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { authApi } from "../api/auth.js";
import { fieldErrors } from "../api/client.js";

// 백엔드가 400 검증 실패 시 data에 필드별 사유를 담아 보낸다. 있으면 함께 보여 준다.
function withFields(err, fallback) {
  const base = err?.message || fallback;
  const fields = fieldErrors(err);
  return fields ? `${base} (${fields})` : base;
}
import { useToast } from "../hooks/useToast.js";
import { useIsMobile } from "../hooks/useIsMobile.js";
import Icon from "../components/Icon.jsx";

// 백엔드 SignupRequest.password의 @Pattern과 같은 식이다. 한쪽만 바꾸면 통과·거부가 어긋난다.
const PW_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9])[\x21-\x7E]{6,20}$/;

// 가입 코드 규칙은 비밀번호 찾기와 같다: 영문 대소문자+숫자 6자리, 대소문자 구분.
const CODE_LENGTH = 6;
// 코드 재발급은 60초에 한 번. 더 빨리 누르면 백엔드가 429를 낸다.
const RESEND_SECONDS = 60;

export default function SignupPage() {
  const isMobile = useIsMobile();
  const [step, setStep]           = useState("email"); // email | code
  const [email, setEmail]         = useState("");
  const [code, setCode]           = useState("");
  const [pw, setPw]               = useState("");
  const [confirm, setConfirm]     = useState("");
  const [adminCode, setAdminCode] = useState("");
  const [loading, setLoading]     = useState(false);
  const [sending, setSending]     = useState(false);
  const [error, setError]         = useState("");
  const [expired, setExpired]     = useState(false);  // 코드를 다시 받아야 하는 상태
  const [cooldown, setCooldown]   = useState(0);      // 재발급까지 남은 초
  const toast = useToast();
  const navigate = useNavigate();

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setInterval(() => setCooldown((n) => (n <= 1 ? 0 : n - 1)), 1000);
    return () => clearInterval(t);
  }, [cooldown]);

  const sendCode = async () => {
    if (!email.trim()) { setError("이메일을 입력해주세요."); return; }
    setSending(true);
    setError("");
    try {
      await authApi.signupCode(email.trim());
      // 재발급하면 이전 코드는 무효가 되므로 입력값을 비운다
      setCode("");
      setExpired(false);
      setCooldown(RESEND_SECONDS);
      setStep("code");
      toast.success("인증 코드를 발송했습니다. 메일함을 확인해주세요.");
    } catch (err) {
      // 409 이미 가입된 이메일 / 429 너무 자주 요청
      if (err?.status === 429) setCooldown(RESEND_SECONDS);
      setError(withFields(err, "인증 코드 발송에 실패했습니다."));
    } finally {
      setSending(false);
    }
  };

  const submit = async () => {
    if (!code.trim()) { setError("인증 코드를 입력해주세요."); return; }
    if (code.trim().length !== CODE_LENGTH) {
      setError(`인증 코드는 ${CODE_LENGTH}자리입니다.`);
      return;
    }
    if (!pw) { setError("비밀번호를 입력해주세요."); return; }
    if (pw !== confirm) { setError("비밀번호가 일치하지 않습니다."); return; }
    if (!PW_PATTERN.test(pw)) {
      setError("비밀번호는 영문·숫자·특수문자를 각각 1개 이상 포함한 6~20자여야 합니다(공백·한글 불가).");
      return;
    }
    setLoading(true);
    setError("");
    try {
      // 코드는 대소문자를 구분하므로 공백만 제거하고 그대로 보낸다
      await authApi.signup(email.trim(), pw, code.trim(), adminCode || undefined);
      toast.success("가입 완료! 로그인 해주세요.");
      navigate("/login", { replace: true });
    } catch (err) {
      // 429(5회 초과)는 코드를 다시 받아야 한다. 400(불일치·만료)은 다시 입력하면 될 수도 있어
      // 서버 메시지를 그대로 보여 주고 재발급 안내는 항상 노출한다.
      setExpired(err?.status === 429);
      setError(withFields(err, "회원가입에 실패했습니다."));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ ...S.page, ...(isMobile ? S.pageMobile : {}) }}>
      <div style={S.glow} />
      {/* 어디에 가입·접속하는지만 밝히고 설명은 두지 않는다 */}
      <div style={S.stack}>
        <div style={S.brandRow}>
          <span style={S.brandMark}><Icon name="train" size={20} /></span>
          <span style={S.brandName}>또타24 현행화 시스템</span>
        </div>

        <div style={{ ...S.card, ...(isMobile ? S.cardMobile : {}) }}>
          <h2 style={S.title}>회원가입</h2>
          <p style={S.desc}>
            {step === "email"
              ? "가입할 이메일로 인증 코드를 보내 드립니다."
              : "메일로 받은 인증 코드와 비밀번호를 입력해주세요."}
          </p>

          <div style={{ marginBottom: 14 }}>
            <label style={S.label}>이메일</label>
            <input style={{ ...S.input, ...(step === "code" ? S.inputLocked : {}) }}
              type="email" placeholder="admin@seoulmetro.co.kr"
              value={email} readOnly={step === "code"}
              onChange={(e) => setEmail(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && step === "email") sendCode(); }} />
          </div>

          {step === "email" && (
            <button style={{ ...S.btn, marginTop: 4, opacity: sending ? 0.7 : 1 }}
              onClick={sendCode} disabled={sending}>
              {sending ? "발송 중..." : "인증코드 받기"}
            </button>
          )}

          {step === "code" && (
            <>
              <div style={{ marginBottom: 14 }}>
                <label style={S.label}>인증 코드 (영문 대소문자·숫자 {CODE_LENGTH}자리)</label>
                <div style={{ display: "flex", gap: 8 }}>
                  <input style={{ ...S.input, letterSpacing: 2 }} type="text"
                    maxLength={CODE_LENGTH} placeholder="메일로 받은 코드"
                    value={code} onChange={(e) => setCode(e.target.value)} />
                  <button style={{ ...S.btnGhost, opacity: sending || cooldown > 0 ? 0.5 : 1 }}
                    onClick={sendCode} disabled={sending || cooldown > 0}>
                    {cooldown > 0 ? `${cooldown}초` : "다시 받기"}
                  </button>
                </div>
                <p style={S.hint}>
                  코드는 30분간 유효하고 대소문자를 구분합니다. 5회 틀리면 다시 받아야 합니다.
                  <br />메일이 오지 않으면 스팸함을 확인해 주세요.
                </p>
              </div>

              {[
                { label: "비밀번호 (영문·숫자·특수문자 각각 1개 이상, 6~20자)", type: "password", ph: "예: Abc123!@", val: pw, set: setPw },
                { label: "비밀번호 확인", type: "password", ph: "비밀번호 재입력", val: confirm, set: setConfirm },
              ].map((f) => (
                <div key={f.label} style={{ marginBottom: 14 }}>
                  <label style={S.label}>{f.label}</label>
                  <input style={S.input} type={f.type} placeholder={f.ph}
                    value={f.val} onChange={(e) => f.set(e.target.value)} />
                </div>
              ))}

              <div style={{ marginBottom: 14 }}>
                <label style={S.label}>관리자 코드 (선택 — 있으면 즉시 관리자로 가입됩니다)</label>
                <input style={S.input} type="text" placeholder="관리자가 아니면 비워두세요"
                  value={adminCode} onChange={(e) => setAdminCode(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") submit(); }} />
              </div>

              <button style={{ ...S.btn, marginTop: 4, opacity: loading ? 0.7 : 1 }}
                onClick={submit} disabled={loading}>
                {loading ? "처리 중..." : "가입하기"}
              </button>
            </>
          )}

          {error && <p style={S.error}>{error}</p>}
          {expired && (
            <p style={S.hint}>코드를 다시 받아 주세요. 위 <strong>다시 받기</strong> 버튼을 누르면 새 코드가 발송됩니다.</p>
          )}

          <div style={{ textAlign: "center", marginTop: 16, fontSize: 13 }}>
            <Link to="/login" style={S.link}>로그인으로 돌아가기</Link>
          </div>
        </div>

        {/* 운영 주체 표기. 공식 로고 파일을 받으면 이 자리에 이미지를 넣는다.
            (public/ 에 두고 import.meta.env.BASE_URL 로 경로를 잡으면 배포에서도 맞는다) */}
        <div style={S.orgLockup}>
          <span style={S.orgKo}>서울교통공사</span>
          <span style={S.orgEn}>Seoul Metro</span>
        </div>
      </div>
    </div>
  );
}

const S = {
  page: { minHeight: "100vh", background: "#11315B", display: "flex", alignItems: "center", justifyContent: "center", padding: 24, position: "relative", overflow: "hidden" },
  pageMobile: { padding: 16 },
  // 흩어진 도형 대신 넓게 번지는 빛으로 깊이만 준다 (로그인 화면과 같은 결)
  glow: { position: "absolute", top: "-30%", left: "50%", transform: "translateX(-50%)", width: 900, height: 900,
          background: "radial-gradient(circle, rgba(59,130,246,.42) 0%, rgba(59,130,246,0) 66%)", pointerEvents: "none" },

  orgLockup: { display: "flex", flexDirection: "column", alignItems: "center", gap: 2, marginTop: 4 },
  orgKo:     { fontSize: 13, fontWeight: 600, color: "rgba(255,255,255,.72)", letterSpacing: "-.01em" },
  orgEn:     { fontSize: 9.5, fontWeight: 600, color: "rgba(255,255,255,.44)", letterSpacing: ".14em", textTransform: "uppercase" },

  stack: { position: "relative", zIndex: 10, width: "100%", maxWidth: 400,
           display: "flex", flexDirection: "column", alignItems: "center", gap: 22 },
  brandRow:  { display: "flex", alignItems: "center", gap: 10 },
  brandMark: { display: "flex", alignItems: "center", justifyContent: "center", width: 34, height: 34,
               borderRadius: 10, background: "rgba(255,255,255,.14)", color: "#fff" },
  brandName: { fontSize: 15, fontWeight: 700, color: "#fff", letterSpacing: "-.02em" },

  card: { width: "100%", background: "#fff", borderRadius: 18, padding: "38px 36px",
          boxShadow: "0 28px 70px -24px rgba(2,8,23,.45)" },
  cardMobile: { padding: "32px 22px", borderRadius: 16 },
  title: { fontSize: 23, fontWeight: 700, color: "#0F172A", letterSpacing: "-.03em", marginBottom: 6 },
  desc: { fontSize: 13, color: "#94A3B8", marginBottom: 26, lineHeight: 1.6 },
  label: { display: "block", fontSize: 12, fontWeight: 600, color: "#475569", marginBottom: 6 },
  input: { width: "100%", height: 46, padding: "0 14px", background: "#F8FAFC", border: "1.5px solid #E2E8F0", borderRadius: 12, fontSize: 14, fontFamily: "inherit", outline: "none", boxSizing: "border-box", color: "#0F172A" },
  btn: { width: "100%", height: 48, background: "#0D3B7A", color: "#fff", border: "none", borderRadius: 12, fontSize: 14.5, fontWeight: 600, letterSpacing: "-.01em", cursor: "pointer", fontFamily: "inherit", boxShadow: "0 8px 20px -8px rgba(13,59,122,.6)" },
  inputLocked: { background: "#F1F5F9", color: "#64748B" },
  btnGhost: { flexShrink: 0, height: 46, padding: "0 16px", background: "#fff", color: "#0D3B7A", border: "1.5px solid #CBD5E1", borderRadius: 12, fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "inherit", whiteSpace: "nowrap" },
  hint: { fontSize: 11.5, color: "#94A3B8", lineHeight: 1.65, marginTop: 7 },
  error: { fontSize: 12.5, color: "#BE123C", background: "#FFF1F2", border: "1px solid #FECDD3", borderRadius: 10, padding: "9px 12px", marginTop: 12, lineHeight: 1.5 },
  link: { color: "#0D3B7A", fontWeight: 600, cursor: "pointer", textDecoration: "none" },
};
