import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { authApi } from "../api/auth.js";
import { fieldErrors } from "../api/client.js";

// 백엔드가 400 검증 실패 시 data에 필드별 사유를 담아 보낸다. 있으면 함께 보여 준다.
function withFields(err, fallback) {
  const base = err?.message || fallback;
  const fields = fieldErrors(err);
  return fields ? `${base} (${fields})` : base;
}
import { useToast } from "../hooks/useToast.js";

// 새 비밀번호 규칙: 영문(대소문자)·숫자·특수문자를 각각 1개 이상 포함한 6~20자.
// 특수문자는 키보드의 32개 모두 되고 공백·한글은 안 된다.
// 문자 클래스에 A-Z가 없으므로 대문자가 들어가면 통과하지 못한다 (백엔드 정규식과 동일).
// 백엔드 PasswordResetConfirmRequest.newPassword의 @Pattern과 같은 식이다.
const PW_PATTERN = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9])[\x21-\x7E]{6,20}$/;

const CODE_LENGTH = 6;

// 코드를 다시 발급받아야 하는 상황인지 판단한다.
// 백엔드가 코드 불일치·만료·형식 오류를 모두 400으로 묶어서 내려주므로
// 상태 코드만으로는 "다시 입력하면 되는 경우"와 "재발급이 필요한 경우"를 가를 수 없다.
// API 명세 변경 문서에 "message 문구로 분기하지 말고 HTTP 상태 코드를 본다"고 돼 있어
// 문자열 매칭은 쓰지 않는다. 확실히 재발급이 필요한 429만 강조하고,
// 400은 서버 메시지를 그대로 보여 주되 재발급 안내는 항상 노출한다.
function needsNewCode(err) {
  return err?.status === 429;
}

export default function ResetPage() {
  const [step, setStep]       = useState("email"); // email | code
  const [email, setEmail]     = useState("");
  const [code, setCode]       = useState("");
  const [pw, setPw]           = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError]     = useState("");
  const [expired, setExpired] = useState(false); // 재발급이 필요한 상태
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const toast = useToast();

  const sendCode = async () => {
    if (!email.trim()) { setError("이메일을 입력해주세요."); return; }
    setLoading(true);
    setError("");
    try {
      await authApi.resetPassword(email.trim());
      // 재발급하면 이전 코드는 무효가 되므로 입력값을 비운다
      setCode("");
      setExpired(false);
      setStep("code");
      toast.success("인증 코드를 발송했습니다. 메일함을 확인해주세요.");
    } catch (err) {
      setError(withFields(err, "인증 코드 발송에 실패했습니다."));
    } finally {
      setLoading(false);
    }
  };

  const submit = async () => {
    // 코드는 대소문자를 구분하므로 case 변환 없이 앞뒤 공백만 제거한다
    const token = code.trim();

    if (token.length !== CODE_LENGTH) {
      setError(`인증 코드 ${CODE_LENGTH}자리를 입력해주세요.`);
      return;
    }
    if (pw !== confirm) {
      setError("비밀번호가 일치하지 않습니다.");
      return;
    }
    if (!PW_PATTERN.test(pw)) {
      setError("비밀번호는 영문·숫자·특수문자를 각각 1개 이상 포함한 6~20자여야 합니다(공백·한글 불가).");
      return;
    }

    setLoading(true);
    setError("");
    try {
      await authApi.resetPasswordConfirm(email.trim(), token, pw);
      toast.success("비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.");
      navigate("/login", { replace: true });
    } catch (err) {
      // 백엔드 메시지를 그대로 보여주고, 재발급이 필요한 경우만 안내를 덧붙인다
      setError(withFields(err, "비밀번호 재설정에 실패했습니다."));
      setExpired(needsNewCode(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={S.page}>
      <div style={S.left}>
        <span style={{ fontSize: 52 }}>🚇</span>
        <h1 style={S.brand}>비밀번호 찾기</h1>
        <p style={S.tagline}>
          {step === "email"
            ? "등록된 이메일로 인증 코드를 발송합니다."
            : <>메일로 받은 6자리 인증 코드와<br />새 비밀번호를 입력하세요.</>}
        </p>
      </div>

      <div style={S.right}>
        <div style={S.box}>
          {step === "email" ? (
            <>
              <h2 style={S.title}>비밀번호 찾기</h2>
              <p style={S.desc}>가입한 이메일을 입력하면 인증 코드를 발송합니다.</p>

              <label style={S.label}>이메일</label>
              <input style={S.input} type="email"
                placeholder="admin@seoulmetro.co.kr"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") sendCode(); }} />

              {error && <p style={S.errorText}>{error}</p>}

              <button style={{ ...S.btn, marginTop: 20, opacity: loading ? 0.7 : 1 }}
                onClick={sendCode} disabled={loading}>
                {loading ? "발송 중..." : "인증 코드 발송"}
              </button>
            </>
          ) : (
            <>
              <h2 style={S.title}>비밀번호 재설정</h2>
              <p style={S.desc}>
                <strong style={{ color: "#374151" }}>{email.trim()}</strong> 으로 보낸
                인증 코드를 입력하세요. 코드는 30분간 유효합니다.
              </p>

              <label style={S.label}>인증 코드 {CODE_LENGTH}자리</label>
              <input style={{ ...S.input, ...S.codeInput, marginBottom: 6 }} type="text"
                placeholder="aB3xY9"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                maxLength={CODE_LENGTH}
                autoCapitalize="off"
                autoCorrect="off"
                autoComplete="one-time-code"
                spellCheck={false} />
              <p style={S.hint}>영문 대소문자와 숫자 조합이며, 대소문자를 구분합니다.</p>

              <label style={S.label}>새 비밀번호</label>
              <input style={{ ...S.input, marginBottom: 14 }} type="password"
                placeholder="영문·숫자·특수문자 각각 1개 이상, 6~20자"
                value={pw} onChange={(e) => setPw(e.target.value)} />

              <label style={S.label}>새 비밀번호 확인</label>
              <input style={S.input} type="password"
                placeholder="비밀번호 재입력"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") submit(); }} />

              {error && (
                <div style={S.errorBox}>
                  <p style={{ margin: 0 }}>{error}</p>
                  <p style={{ margin: "6px 0 0", color: "#92400E" }}>
                    {expired
                      ? "코드를 다시 받아야 합니다. 새로 발급하면 이전 코드는 사용할 수 없습니다."
                      : "코드가 만료됐거나 이미 사용한 코드라면 아래에서 다시 받으실 수 있습니다."}
                  </p>
                </div>
              )}

              <button style={{ ...S.btn, marginTop: 20, opacity: loading ? 0.7 : 1 }}
                onClick={submit} disabled={loading}>
                {loading ? "변경 중..." : "비밀번호 변경"}
              </button>

              <div style={{ display: "flex", justifyContent: "space-between", marginTop: 12, fontSize: 13 }}>
                <button style={{ ...S.linkBtn, fontWeight: expired ? 600 : 400 }}
                  onClick={sendCode} disabled={loading}>
                  코드 다시 받기
                </button>
                <button style={S.linkBtn}
                  onClick={() => { setStep("email"); setError(""); setExpired(false); }}
                  disabled={loading}>
                  이메일 다시 입력
                </button>
              </div>
            </>
          )}

          <div style={{ textAlign: "center", marginTop: 16, fontSize: 13 }}>
            <Link to="/login" style={S.link}>로그인으로 돌아가기</Link>
          </div>
        </div>
      </div>
    </div>
  );
}

const S = {
  page: { display: "flex", minHeight: "100vh", background: "linear-gradient(135deg, #0D3B7A 0%, #1565C0 100%)" },
  left: { flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", padding: "60px 56px", color: "#fff" },
  brand: { fontSize: 32, fontWeight: 700, lineHeight: 1.3, margin: "20px 0 16px" },
  tagline: { fontSize: 15, color: "rgba(255,255,255,0.75)", lineHeight: 1.8 },
  right: { width: 480, background: "#fff", display: "flex", alignItems: "center", justifyContent: "center", padding: "40px 32px" },
  box: { width: "100%", maxWidth: 380 },
  title: { fontSize: 24, fontWeight: 700, color: "#111827", marginBottom: 6 },
  desc: { fontSize: 13, color: "#6B7280", marginBottom: 28, lineHeight: 1.6 },
  label: { display: "block", fontSize: 12, fontWeight: 500, color: "#374151", marginBottom: 5 },
  input: { width: "100%", height: 44, padding: "0 14px", border: "1.5px solid #D1D5DB", borderRadius: 8, fontSize: 13, fontFamily: "inherit", outline: "none", boxSizing: "border-box" },
  codeInput: { fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: 20, letterSpacing: ".3em", textAlign: "center" },
  hint: { fontSize: 11, color: "#9CA3AF", margin: "0 0 14px" },
  btn: { width: "100%", height: 46, background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  link: { color: "#1976D2", cursor: "pointer", textDecoration: "underline" },
  linkBtn: { background: "none", border: "none", padding: 0, color: "#1976D2", cursor: "pointer", textDecoration: "underline", fontSize: 13, fontFamily: "inherit" },
  errorText: { fontSize: 12, color: "#DC2626", marginTop: 8 },
  errorBox: { marginTop: 12, padding: "10px 12px", background: "#FEF2F2", border: "1px solid #FECACA", borderRadius: 8, fontSize: 12, color: "#B91C1C", lineHeight: 1.6 },
};
