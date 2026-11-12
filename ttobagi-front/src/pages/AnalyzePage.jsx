import { useState, useEffect, useRef, useCallback } from "react";
import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, Cell, ResponsiveContainer } from "recharts";
import { dashApi } from "../api/dashboard.js";
import { fieldErrors } from "../api/client.js";
import { faqApi } from "../api/faq.js";
import MatchedFaqBadge from "../components/MatchedFaqBadge.jsx";
import { toPlainText } from "../utils/text.js";
import { useAuth } from "../hooks/useAuth.js";
import { useToast } from "../hooks/useToast.js";
import { useIsMobile } from "../hooks/useIsMobile.js";
import Icon from "../components/Icon.jsx";
import FitSelect from "../components/FitSelect.jsx";
import { scopeSentence, scopeCaption, legacyDefaultNotice, completionNotice, emptyFinishedNotice, MODE_OPTIONS } from "../utils/analysisScope.js";
import { PHASES, phaseIndex, phaseLabel, unitProgressText, unitPercent, elapsedText, delayNotice } from "../utils/analysisProgress.js";

// Spring의 spring.servlet.multipart.max-file-size=50MB 와 동일하게 맞춘다
const HISTORY_SIZE = 10;

// 동의어 종류별 색. 색 하나가 뜻 하나를 맡아야 글자를 읽지 않고도 구분된다.
// FAQ 관리 목록의 키워드(파랑)·유사어(보라)와 같은 규칙을 쓴다.
const SYNONYM_KIND = {
  SYNONYM: { label: "유사어", background: "#F5F3FF", color: "#5B21B6", border: "#DDD6FE" },
  TYPO:    { label: "오타",   background: "#FEF3C7", color: "#92400E", border: "#FDE68A" },
  ABBR:    { label: "약어",   background: "#ECFEFF", color: "#0E7490", border: "#A5F3FC" },
};

// 백엔드 ReviewStatus: PENDING / ACCEPTED / REJECTED / APPLIED
const REVIEW_STATUS = {
  APPLIED:  { label: "반영됨", text: "#047857", dot: "#10B981", bg: "#ECFDF5", border: "#A7F3D0" },
  ACCEPTED: { label: "승인됨", text: "#1D4ED8", dot: "#3B82F6", bg: "#EFF6FF", border: "#BFDBFE" },
  REJECTED: { label: "반려됨", text: "#BE123C", dot: "#F43F5E", bg: "#FFF1F2", border: "#FECDD3" },
  PENDING:  { label: "대기",   text: "#475569", dot: "#94A3B8", bg: "#F8FAFC", border: "#E2E8F0" },
};

// 이모지 대신 작은 점으로 상태를 구분한다. 색이 뜻을 전하고 글자는 그대로 읽힌다.
function StatusChip({ status }) {
  const s = REVIEW_STATUS[status] || REVIEW_STATUS.PENDING;
  return (
    <span style={{
      display: "inline-flex", alignItems: "center", gap: 6,
      fontSize: 11, fontWeight: 600, letterSpacing: "-.01em",
      padding: "4px 10px", borderRadius: 999,
      background: s.bg, color: s.text, border: `1px solid ${s.border}`,
      whiteSpace: "nowrap",
    }}>
      <span style={{ width: 5, height: 5, borderRadius: "50%", background: s.dot, flexShrink: 0 }} />
      {s.label}
    </span>
  );
}

// 백엔드 AnalysisJob.Status 5종. 진행 중은 파랑, 완료 초록, 실패 빨강.
// 영문 원문을 그대로 보여주면 읽는 사람이 어느 단계인지 알기 어려워 한글 이름을 붙인다.
const JOB_STATUS = {
  PENDING:       { label: "대기 중",   background: "#DBEAFE", color: "#1E40AF" },
  PREPROCESSING: { label: "전처리 중", background: "#DBEAFE", color: "#1E40AF" },
  ANALYZING:     { label: "분석 중",   background: "#DBEAFE", color: "#1E40AF" },
  COMPLETED:     { label: "완료",      background: "#D1FAE5", color: "#065F46" },
  FAIL:          { label: "실패",      background: "#FDECEA", color: "#B91C1C" },
  CANCELED:      { label: "취소됨",    background: "#F1F5F9", color: "#475569" },
};

// 한 줄짜리 단계 표시. 지나간 것은 체크, 지금은 파랑, 아직은 회색.
function PhaseRow({ label, state, order }) {
  const isDone = state === "done";
  const isRun  = state === "running";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 13 }}>
      <div style={{
        width: 24, height: 24, borderRadius: "50%", display: "flex",
        alignItems: "center", justifyContent: "center", fontSize: 11, fontWeight: 600,
        background: isDone ? "#ECFDF5" : isRun ? "#EFF6FF" : "#F3F4F6",
        color:      isDone ? "#047857" : isRun ? "#1D4ED8" : "#9CA3AF",
      }}>
        {isDone ? <Icon name="check" size={13} strokeWidth={2.6} /> : order}
      </div>
      <span style={{ color: isDone ? "#111827" : isRun ? "#1D4ED8" : "#9CA3AF", fontWeight: isDone || isRun ? 500 : 400 }}>
        {label}
      </span>
      {isRun && <span style={{ fontSize: 11, color: "#1D4ED8" }}>처리 중...</span>}
    </div>
  );
}

// AI가 보고한 phase로 단계를 그린다.
//
// 보고가 없으면(progressDetail=null, 과거 작업이나 아직 안 보낸 경우) 예전처럼
// 상태 콜백 기준 다섯 단계로 떨어진다. 새 정보가 없다고 화면이 비면 안 된다.
//
// 지금 단계보다 앞이면 지났다고 본다. 건너뛴 단계도 지난 것으로 그린다 —
// 조건에 안 맞아 생략하는 것은 정상이고, 비워 두면 멈춘 것처럼 보인다(계약 4절).
function PhaseList({ detail, step, running, done }) {
  const FALLBACK = ["데이터 전처리", "문장 임베딩", "군집화 (HDBSCAN)", "FAQ 초안 생성", "검증 완료"];

  if (!detail?.phase) {
    return (
      <>
        {FALLBACK.map((label, i) => (
          <PhaseRow key={label} label={label} order={i + 1}
            state={step >= i ? "done" : step === i && running ? "running" : "todo"} />
        ))}
      </>
    );
  }

  const current = phaseIndex(detail.phase);
  return (
    <>
      {PHASES.map((p, i) => (
        <PhaseRow key={p.key} label={p.label} order={i + 1}
          state={done || i < current ? "done" : i === current ? (running ? "running" : "done") : "todo"} />
      ))}
    </>
  );
}

// 무엇을 분석한 결과인지 한 줄로 밝힌다.
// 통계 카드는 올린 전체를 세고, 군집도·급증 키워드는 그중 골라낸 질문만 본다.
// 두 숫자가 다른 이유를 적어 두지 않으면 화면이 틀린 것처럼 보인다.
// 과거 결과에는 범위 정보가 없어 안내도 띄우지 않는다(계약 3절: 추정해 채우지 않는다).
function ScopeNotice({ result }) {
  const sentence = scopeSentence(result);
  if (!sentence) return null;
  const legacy = legacyDefaultNotice(result);
  return (
    <div style={S.scopeNotice}>
      <div>{sentence}</div>
      {legacy && <div style={{ marginTop: 4, opacity: 0.8 }}>{legacy}</div>}
    </div>
  );
}

// 결과가 비어 있을 때 무슨 일이 있었는지 알린다.
//
// 대상 0건·짧은 문장뿐·소량·묶이지 않음은 모두 정상 완료다(계약 3절).
// "분석을 먼저 실행해주세요"를 띄우면 분석을 안 한 것처럼 보이고,
// 오류처럼 적으면 멀쩡한 파일을 다시 올리게 만든다.
function EmptyResult({ result, icon = "analyze" }) {
  // 결과가 아예 없으면 아직 안 돌린 것이고, 결과는 왔는데 비었으면 끝난 것이다.
  // 둘을 같은 문구로 덮으면 끝난 분석을 안 돌렸다고 말하게 된다.
  const notice = result ? (completionNotice(result) || emptyFinishedNotice(result)) : null;
  const title = notice ? notice.title : "분석을 먼저 실행해주세요.";
  return (
    <div style={{ ...S.card, ...S.cardBody, textAlign: "center", padding: 48, color: "#9CA3AF" }}>
      <span style={{ display: "inline-flex", alignItems: "center", justifyContent: "center", width: 44, height: 44, borderRadius: "50%", background: "#F3F4F6", color: "#9CA3AF", marginBottom: 12 }}>
        <Icon name={icon} size={21} />
      </span>
      <div style={{ color: "#374151", fontWeight: 500 }}>{title}</div>
      {notice && (
        <div style={{ marginTop: 8, fontSize: 12, lineHeight: 1.6, maxWidth: 460, marginLeft: "auto", marginRight: "auto" }}>
          {notice.body}
        </div>
      )}
    </div>
  );
}

// 모르는 상태가 와도 글자는 그대로 보여 준다. 숨기면 무슨 일인지 알 수 없다.
function jobStatus(status) {
  return JOB_STATUS[status] || { label: status, background: "#DBEAFE", color: "#1E40AF" };
}

// requestedAt은 백엔드가 'Z'를 붙여 UTC로 보낸다(AnalyzeHistoryResponse @JsonFormat,
// 서버 JVM도 TimeZone.setDefault(UTC)). Date가 UTC로 정확히 읽는다.
function formatDateTime(value) {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
const MAX_UPLOAD_MB = 50;
const MAX_UPLOAD_BYTES = MAX_UPLOAD_MB * 1024 * 1024;

function formatElapsed(sec) {
  const m = Math.floor(sec / 60);
  const r = sec % 60;
  return m > 0 ? `${m}분 ${String(r).padStart(2, "0")}초 경과` : `${r}초 경과`;
}

const CLUSTER_COLORS = [
  "#1565C0","#43A047","#E53935","#FB8C00","#8E24AA",
  "#00838F","#F4511E","#3949AB","#00897B","#C0CA33",
];

function ClusterScatterPlot({ points, clusterNames }) {
  // HDBSCAN은 어느 군집에도 속하지 않는 질의를 label -1(노이즈)로 남긴다.
  // 노이즈는 "해석할 군집"이 아니므로 색 배정에서 빼고 회색으로 눌러 둔다.
  // 예전에는 clusterNames 순서대로 색을 돌려서 -1이 첫 번째 진한 파랑을 가져가
  // 노이즈가 가장 눈에 띄는 군집처럼 보였다.
  const NOISE_COLOR = "#D1D5DB";
  const colorMap = {};
  let colorIndex = 0;
  clusterNames.forEach((c) => {
    colorMap[c.clusterLabel] =
      c.clusterLabel < 0 ? NOISE_COLOR : CLUSTER_COLORS[colorIndex++ % CLUSTER_COLORS.length];
  });
  const colorOf = (label) => colorMap[label] ?? (label < 0 ? NOISE_COLOR : "#9CA3AF");

  // 범례는 크기순으로 보여 주고 노이즈는 항상 마지막에 둔다
  const legend = [...clusterNames].sort((a, b) => {
    if (a.clusterLabel < 0) return 1;
    if (b.clusterLabel < 0) return -1;
    return (b.size || 0) - (a.size || 0);
  });
  const nameOf = (label) =>
    clusterNames.find((c) => c.clusterLabel === label)?.name
    || (label < 0 ? "노이즈" : `클러스터 ${label}`);

  // 노이즈를 먼저 그려 실제 군집이 위에 오게 한다 (recharts는 배열 순서대로 렌더한다)
  const ordered = [...points].sort((a, b) => (a.clusterLabel < 0 ? -1 : 0) - (b.clusterLabel < 0 ? -1 : 0));

  return (
    <div>
      <div style={{ width: "100%", height: 400 }}>
        <ResponsiveContainer>
          <ScatterChart>
            <XAxis dataKey="x" type="number" name="X" tick={{ fontSize: 10 }} />
            <YAxis dataKey="y" type="number" name="Y" tick={{ fontSize: 10 }} />
            <Tooltip
              cursor={{ strokeDasharray: "3 3" }}
              content={({ payload }) => {
                if (!payload?.length) return null;
                const d = payload[0]?.payload;
                return (
                  <div style={{ background: "#fff", border: "1px solid #E5E7EB", borderRadius: 8, padding: "8px 12px", fontSize: 12, maxWidth: 220 }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 600, marginBottom: 4 }}>
                      <span style={{ width: 8, height: 8, borderRadius: "50%", background: colorOf(d?.clusterLabel) }} />
                      {nameOf(d?.clusterLabel)}
                    </div>
                    <div style={{ color: "#6B7280", wordBreak: "break-all" }}>{d?.text?.slice(0, 50)}</div>
                  </div>
                );
              }}
            />
            <Scatter data={ordered} isAnimationActive={false}>
              {ordered.map((p, i) => (
                <Cell
                  key={i}
                  fill={colorOf(p.clusterLabel)}
                  opacity={p.clusterLabel < 0 ? 0.35 : 0.75}
                />
              ))}
            </Scatter>
          </ScatterChart>
        </ResponsiveContainer>
      </div>

      {legend.length > 0 && (
        <div style={{ display: "flex", flexWrap: "wrap", gap: "6px 14px", marginTop: 12, paddingTop: 12, borderTop: "1px solid #F3F4F6" }}>
          {legend.map((c) => (
            <span
              key={c.clusterId ?? c.clusterLabel}
              title={(c.topKeywords || []).join(", ")}
              style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12, color: c.clusterLabel < 0 ? "#9CA3AF" : "#374151" }}
            >
              <span style={{ width: 9, height: 9, borderRadius: "50%", background: colorOf(c.clusterLabel), flexShrink: 0 }} />
              {c.name ? `${c.name} (C${c.clusterLabel})` : `클러스터 ${c.clusterLabel}`}
              <span style={{ color: "#9CA3AF" }}>({(c.size || 0).toLocaleString()})</span>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

export default function AnalyzePage() {
  const isMobile = useIsMobile();
  const [tab, setTab]               = useState("upload");
  const [file, setFile]             = useState(null);
  const [drag, setDrag]             = useState(false);
  const [uploading, setUploading]   = useState(false);
  const [uploadResult, setUploadResult] = useState(null);
  const [uploadErrors, setUploadErrors] = useState(null); // 업로드 400의 행별 오류
  // 업로드 영역에 보여 줄 파일 이름. 새로 고른 파일이 없을 때 쓴다.
  // 이력에서 연 분석의 파일을 여기에 담아, 왼쪽(업로드)과 오른쪽(이력)이 같은 분석을 가리키게 한다.
  const [openedFileName, setOpenedFileName] = useState(null);
  const [running, setRunning]       = useState(false);
  const [step, setStep]             = useState(-1);
  const [done, setDone]             = useState(false);
  const [candidates, setCandidates] = useState([]);
  const [history, setHistory]       = useState([]);
  // 이력 패널: scope=mine(기본) / all, 완료만 보기, 페이지
  const [historyScope, setHistoryScope]   = useState("mine");
  const [onlyCompleted, setOnlyCompleted] = useState(false);
  const [historyPage, setHistoryPage]     = useState(0);
  const [historyInfo, setHistoryInfo]     = useState({ totalCount: 0, totalPages: 0 });
  const [historyLoading, setHistoryLoading] = useState(false);
  const [deleting, setDeleting]           = useState(null);
  const [analyzeResult, setAnalyzeResult] = useState(null);
  const [analysisId, setAnalysisId] = useState(null);
  const [reviewing, setReviewing]   = useState(null); // 처리 중인 candidateId
  const [rejectTarget, setRejectTarget] = useState(null); // 반려 사유 입력 대상 후보
  const [editTarget, setEditTarget]     = useState(null); // 수정 중인 후보
  const [rejectReason, setRejectReason] = useState("");
  // 후보 카드에서 "묶인 질문"을 펼쳐 둔 candidateId 집합
  const [openQuestions, setOpenQuestions] = useState(() => new Set());
  // 후보 수정의 카테고리 선택지. GET /faq/categories 가 없으면 빈 배열로 둔다.
  const [categories, setCategories]     = useState([]);
  // 취소 확인 창을 띄울지, 취소 요청을 보내는 중인지
  // AI가 보고한 세부 진행 단계. 보내기 전까지는 null이고, 그때는 단계 없이 진행만 알린다.
  const [progressDetail, setProgressDetail] = useState(null);
  const [cancelAsking, setCancelAsking] = useState(false);
  const [cancelling, setCancelling]     = useState(false);
  const [cancelable, setCancelable]     = useState(false);
  const [startedAt, setStartedAt]   = useState(null); // 분석 시작 시각
  const [elapsed, setElapsed]       = useState(0);    // 경과 초
  const [options, setOptions]       = useState({
    periodStartDate: "",
    periodEndDate: "",
    isMaskingEnabled: true,
    isTranslationEnabled: false,
    // 계약 2절의 기본값. 비워 보내면 백엔드도 같은 값을 쓴다.
    analysisMode: "IMPROVEMENT",
    minClusterSize: "",
    minSamples: "",
  });
  const toast = useToast();
  const { user } = useAuth();

  // SSE 연결과 폴링 타이머를 화면을 떠날 때 반드시 정리한다.
  // 정리하지 않으면 다른 메뉴로 이동한 뒤에도 스트림과 setInterval이 계속 살아남는다.
  const streamRef = useRef(null);
  const pollRef = useRef(null);
  const activeAnalysisRef = useRef(null);

  // 이력 목록을 "마지막에 불러온 조건 그대로" 다시 부르는 함수.
  // watchAnalysis가 loadHistory보다 먼저 선언돼 있어 직접 참조할 수 없고,
  // 조건(페이지·범위·완료만 보기)도 최신 값이어야 해서 ref로 건넨다.
  const refreshHistoryRef = useRef(null);
  // 종료 처리를 이미 끝낸 analysisId. 같은 분석의 COMPLETED/FAIL을
  // 두 번 처리해 알림이 겹치는 것을 막는다.
  const terminalDoneRef = useRef(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  useEffect(() => () => {
    activeAnalysisRef.current = null;
    streamRef.current?.close();
    stopPolling();
  }, [stopPolling]);

  // 분석 진행 감시. 업로드 직후와, 이력에서 진행 중인 분석을 열었을 때 모두 쓴다.
  // SSE를 먼저 붙이고 끊기면 폴링으로 이어받는다.
  const watchAnalysis = useCallback((id) => {
    activeAnalysisRef.current = id;
    streamRef.current?.close();
    stopPolling();
    setCancelable(false);
    setProgressDetail(null);
    // SSE contains status only; ownership comes from the authenticated status API.
    dashApi.getAnalysisStatus(id).then((r) => {
      if (activeAnalysisRef.current !== id || terminalDoneRef.current === id) return;
      setCancelable(r.data?.cancelable === true);
      // 구독하기 전에 이미 지나간 단계가 있으므로 지금 값을 먼저 받아 둔다
      setProgressDetail(r.data?.progressDetail ?? null);
    }).catch(() => {});

    const loadResults = () => {
      stopPolling();
      setStep(4);
      setDone(true);
      setRunning(false);
      // 끝난 파일을 들고 있을 이유가 없다. 비워야 다음 파일을 고를 때 걸리지 않는다.
      setFile(null);
      faqApi.getCandidates(id).then((r) => {
        if (r.data?.recommendations) setCandidates(r.data.recommendations);
      }).catch((err) => toast.error(err.message || "FAQ 후보를 불러오지 못했습니다."));
      dashApi.getFileAnalyzeResult(id).then((r) => {
        if (r.data) setAnalyzeResult(r.data);
      }).catch((err) => toast.error(err.message || "분석 결과를 불러오지 못했습니다."));
    };

    // 백엔드 AnalysisJob.Status는 PENDING / PREPROCESSING / ANALYZING / COMPLETED / FAIL이고,
    // 구독 즉시 현재 상태를 한 번 보내준다. 화면 단계는 5개인데 상태는 3개뿐이라 대표 지점만 찍는다.
    const applyStatus = (status) => {
      if (activeAnalysisRef.current !== id) return true;
      if (status === "PENDING")       setStep(-1);
      if (status === "PREPROCESSING") setStep(0);
      if (status === "ANALYZING")     setStep(2);

      // 이력 목록의 상태는 목록을 불러온 시점 값이라 그대로 두면 PENDING에 머문다.
      // 전처리·분석으로 넘어가도 뱃지가 안 따라가 "HDBSCAN이 도는데 대기 중"으로 보인다.
      // 목록 전체를 다시 부르면 보던 자리가 튀므로, 이 분석의 줄만 상태를 갈아 끼운다.
      setHistory((rows) => rows.map((row) =>
        row.analysisId === id && row.status !== status ? { ...row, status } : row));

      if (!["COMPLETED", "FAIL", "CANCELED"].includes(status)) return false;

      // 종료는 한 번만 처리한다. SSE가 같은 상태를 다시 보내거나
      // 폴링과 겹쳐도 결과 조회·알림이 두 번 나가지 않게 한다.
      if (terminalDoneRef.current === id) return true;
      terminalDoneRef.current = id;
      setCancelable(false);
      stopPolling();
      streamRef.current?.close();

      if (status === "COMPLETED") {
        loadResults();
      } else if (status === "CANCELED") {
        setRunning(false); setDone(false); setStep(-1);
        setAnalyzeResult(null); setCandidates([]);
      } else {
        setRunning(false);
        toast.error("분석에 실패했습니다.");
      }

      // 이력 목록의 status는 목록을 불러온 시점 값이라 업로드 직후면 PENDING에 머문다.
      // 끝났을 때 다시 불러와야 뱃지가 실제 결과(COMPLETED·FAIL)로 바뀐다.
      refreshHistoryRef.current?.();
      return true;
    };

    // SSE가 끊기면 폴링으로 이어받는다. 중복 시작을 막아야 interval이 여러 개 돌지 않는다.
    const startPolling = () => {
      if (activeAnalysisRef.current !== id) return;
      if (terminalDoneRef.current === id) return;
      if (pollRef.current) return;
      pollRef.current = setInterval(async () => {
        try {
          const statusRes = await dashApi.getAnalysisStatus(id);
          if (activeAnalysisRef.current !== id) return;
          setCancelable(statusRes.data?.cancelable === true);
          setProgressDetail(statusRes.data?.progressDetail ?? null);
          if (applyStatus(statusRes.data?.status)) stopPolling();
        } catch (err) {
          if (activeAnalysisRef.current !== id) return;
          stopPolling();
          setRunning(false);
          toast.error(err.message || "분석 상태 조회에 실패했습니다.");
        }
      }, 3000);
    };

    streamRef.current = dashApi.streamAnalysisStatus(
      id,
      (data) => {
        try {
          applyStatus(JSON.parse(data).status);
        } catch {
          // 상태 JSON이 깨졌으면 스트림을 신뢰할 수 없으니 폴링으로 전환
          startPolling();
        }
      },
      startPolling, // 연결 실패
      startPolling, // 종료 상태 없이 스트림이 끊긴 경우 (타임아웃·네트워크 단절)
      (raw) => {
        // 단계는 상태와 별개 이벤트로 온다. 깨져 와도 상태 흐름은 건드리지 않는다.
        if (activeAnalysisRef.current !== id) return;
        try { setProgressDetail(JSON.parse(raw)); } catch { /* 무시하고 다음 보고를 기다린다 */ }
      }
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopPolling]);

  // AI가 중간 단계를 보고하지 않아 진행률이 멈춘 것처럼 보이므로,
  // 경과 시간을 표시해 작업이 살아 있다는 것을 알린다.
  useEffect(() => {
    if (!running || !startedAt) return;
    const t = setInterval(() => setElapsed(Math.floor((Date.now() - startedAt) / 1000)), 1000);
    return () => clearInterval(t);
  }, [running, startedAt]);

  // 반영(/faq/apply)은 백엔드가 @PreAuthorize("hasRole('ADMIN')")로 막는다
  const isAdmin = user?.role === "ADMIN";

  // 후보가 속한 군집의 질문 목록. 후보의 clusterLabel과 같은 점을 clusteringView.points에서 고른다.
  //
  // 주의: 이 개수가 occurrenceCount와 같지 않을 수 있다. 한 군집에서 후보가 여러 개 나오면
  // (예: 분석 #3의 cluster 3에서 후보 2개) 후보마다 occurrenceCount는 자기 몫이지만
  // points는 군집 전체라 더 많다. 새 후보는 analysisProvenance의 모든 참조 군집을
  // 펼치고, 예전 후보는 대표 군집 하나를 표시한다.
  //
  // text는 마스킹·정규화를 거친 문장이다(예: 열차번호가 [METRO_TRAIN]).
  const clusterPoints = analyzeResult?.clusteringView?.points;
  const questionsOf = (clusterLabel) =>
    (clusterPoints || [])
      .filter((pt) => pt.clusterLabel === clusterLabel)
      .sort((a, b) => (a.sourceLogSeqNum ?? 0) - (b.sourceLogSeqNum ?? 0));

  const toggleQuestions = (candidateId) =>
    setOpenQuestions((prev) => {
      const next = new Set(prev);
      if (next.has(candidateId)) next.delete(candidateId);
      else next.add(candidateId);
      return next;
    });

  const setOption = (key) => (e) =>
    setOptions((prev) => ({
      ...prev,
      [key]: e.target.type === "checkbox" ? e.target.checked : e.target.value,
    }));


  // 이력 목록 조회. scope·status는 백엔드가 받는 파라미터 그대로다.
  const loadHistory = useCallback(async (page, scope, completedOnly) => {
    setHistoryLoading(true);
    try {
      const r = await dashApi.getAnalysisHistory({
        page, size: HISTORY_SIZE, scope,
        status: completedOnly ? "COMPLETED" : undefined,
      });
      setHistory(r.data?.history || []);
      setHistoryInfo({
        totalCount: r.data?.totalCount ?? 0,
        totalPages: r.data?.totalPages ?? 0,
      });
      setHistoryPage(page);
    } catch (err) {
      toast.error(err.message || "분석 이력을 불러오지 못했습니다.");
    } finally {
      setHistoryLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 분석이 끝났을 때 watchAnalysis가 쓸 수 있도록, 지금 보고 있는 조건을 담아 둔다.
  // 보던 페이지·필터를 유지한 채로 다시 불러와야 화면이 튀지 않는다.
  useEffect(() => {
    refreshHistoryRef.current = () => loadHistory(historyPage, historyScope, onlyCompleted);
  }, [loadHistory, historyPage, historyScope, onlyCompleted]);

  // 특정 분석을 화면으로 불러온다 (시각화 탭 + FAQ 추천 탭)
  const openAnalysis = useCallback(async (item) => {
    if (!item?.analysisId) return;
    const id = item.analysisId;
    activeAnalysisRef.current = id;
    streamRef.current?.close();
    stopPolling();
    setCancelable(false);
    setAnalysisId(id);
    setAnalyzeResult(null);
    setCandidates([]);

    // 업로드 영역은 "지금 보고 있는 분석"을 가리켜야 한다.
    // 치우지 않으면 아까 올린 파일이 그대로 남아, 이력에서 다른 분석을 열어도
    // 왼쪽에는 엉뚱한 파일 이름이 붙어 있다.
    setFile(null);
    setUploadResult(null);
    setUploadErrors(null);
    setOpenedFileName(item.fileName || null);

    // 목록의 status는 이력을 불러온 시점 값이라, 그 뒤에 끝난 분석도 진행 중으로 남아 있다.
    // COMPLETED·FAIL은 되돌아가지 않으므로 그대로 믿고, 진행 중으로 적혀 있을 때만
    // 서버에 현재 상태를 물어본다. 묻지 않고 "진행 중입니다"를 띄우면
    // 곧이어 SSE가 FAIL을 전해 알림이 겹친다.
    let status = item.status;
    if (!["COMPLETED", "FAIL", "CANCELED"].includes(status)) {
      try {
        status = (await dashApi.getAnalysisStatus(id)).data?.status || status;
      } catch {
        // 확인하지 못했으면 단정적인 안내를 하지 않는다. 진행 화면만 띄우고
        // 실제 상태는 이어지는 SSE·폴링이 알려 준다.
        status = null;
      }
    }
    // 목록이 낡아 있었으면(적힌 상태와 실제가 다르면) 목록도 맞춰 둔다.
    const staleRow = status && status !== item.status;
    if (activeAnalysisRef.current !== id) return;

    if (status === "CANCELED") {
      setCancelable(false);
      setDone(false); setRunning(false); setStep(-1);
      toast.info("취소된 분석입니다.");
      if (staleRow) refreshHistoryRef.current?.();
      return;
    }

    if (status === "FAIL") {
      setDone(false); setRunning(false); setStep(-1);
      toast.error("실패한 분석입니다. 결과를 불러올 수 없습니다.");
      if (staleRow) refreshHistoryRef.current?.();
      return;
    }
    if (status !== "COMPLETED") {
      // 대기·전처리·분석 중이면 진행 상태를 이어서 보여 준다.
      // 시작 시각은 업로드한 화면에만 있으므로 이력의 requestedAt으로 되살린다.
      // 없거나 미래 시각이면 지금부터 센다(경과가 음수로 보이지 않게).
      const requested = item.requestedAt ? new Date(item.requestedAt).getTime() : NaN;
      setStartedAt(Number.isFinite(requested) && requested <= Date.now() ? requested : Date.now());
      setDone(false); setRunning(true);
      watchAnalysis(id);
      if (status) toast.info("분석이 진행 중입니다.");
      return;
    }

    if (staleRow) refreshHistoryRef.current?.();
    setRunning(false); setDone(true); setStep(4);
    const [resultRes, candRes] = await Promise.allSettled([
      dashApi.getFileAnalyzeResult(id),
      faqApi.getCandidates(id),
    ]);
    if (activeAnalysisRef.current !== id) return;
    if (resultRes.status === "fulfilled" && resultRes.value?.data) setAnalyzeResult(resultRes.value.data);
    if (candRes.status === "fulfilled" && candRes.value?.data?.recommendations) {
      setCandidates(candRes.value.data.recommendations);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopPolling]);

  // 내 이력에서만 삭제한다. 분석 결과·후보·반영된 FAQ는 그대로 남는다.
  // 돌고 있는 분석을 멈춘다.
  // 서버가 작업을 실제로 중단해야 하는 일이라 백엔드 응답을 받고 나서 화면을 정리한다.
  // 먼저 끊고 성공으로 보여 주면, 실패했을 때 사용자는 멈춘 줄 알지만 서버는 계속 돈다.
  const handleCancel = async () => {
    if (!analysisId) return;
    setCancelling(true);
    try {
      await dashApi.cancelAnalysis(analysisId);
      if (activeAnalysisRef.current !== analysisId) {
        setCancelAsking(false);
        toast.success("분석을 취소했습니다.");
        refreshHistoryRef.current?.();
        return;
      }
      terminalDoneRef.current = analysisId;
      setCancelable(false);
      streamRef.current?.close();
      stopPolling();
      setRunning(false);
      setDone(false);
      setStep(-1);
      setAnalyzeResult(null); setCandidates([]);
      setCancelAsking(false);
      toast.success("분석을 취소했습니다.");
      refreshHistoryRef.current?.();
    } catch (err) {
      // 완료 확인 실패를 성공으로 표시하지 않는다. 구독은 유지한다.
      if (err?.status === 404) {
        toast.error("분석을 찾을 수 없습니다.");
      } else if (err?.status === 403) {
        // 분석을 시작한 사람만 취소할 수 있다. 버튼은 cancelable로 가리지만,
        // 누르는 사이에 상태가 바뀌었거나 남의 분석을 열어 둔 경우 여기로 온다.
        setCancelable(false);
        toast.error("분석을 시작한 사람만 취소할 수 있습니다.");
      } else if (err?.status === 503) {
        // 백엔드가 AI 서버의 중단 확인을 받지 못한 경우다(ANALYSIS_CANCEL_PENDING).
        // 취소가 됐는지 안 됐는지 모르는 상태라 단정하지 않고 다시 눌러 보게 한다.
        toast.error("취소가 끝났는지 확인하지 못했습니다. 잠시 후 다시 눌러주세요.");
        refreshHistoryRef.current?.();
      } else if (err?.status === 409) {
        // 누르는 사이에 끝났거나 이미 취소된 분석
        toast.info("이미 끝난 분석입니다.");
        refreshHistoryRef.current?.();
      } else {
        toast.error(err.message || "분석을 취소하지 못했습니다.");
      }
      setCancelAsking(false);
    } finally {
      setCancelling(false);
    }
  };

  const handleDeleteHistory = async (item) => {
    if (!window.confirm("내 이력에서 삭제합니다. 분석 결과와 반영된 FAQ는 그대로 남습니다.")) return;
    setDeleting(item.analysisId);
    try {
      await dashApi.deleteAnalysisHistory(item.analysisId);
      toast.success("분석 이력이 삭제되었습니다.");

      // 보고 있던 분석을 지웠으면 화면을 비운다
      if (analysisId === item.analysisId) {
        streamRef.current?.close();
        stopPolling();
        setAnalysisId(null);
        setAnalyzeResult(null);
        setCandidates([]);
        setDone(false);
        setRunning(false);
        setStep(-1);
      }
      // 마지막 줄을 지워 현재 페이지가 비면 한 페이지 앞으로
      const nextPage = history.length === 1 && historyPage > 0 ? historyPage - 1 : historyPage;
      await loadHistory(nextPage, historyScope, onlyCompleted);
    } catch (err) {
      toast.error(err.message || "분석 이력을 삭제하지 못했습니다.");
    } finally {
      setDeleting(null);
    }
  };

  useEffect(() => {
    let cancelled = false;
    (async () => {
      await loadHistory(0, "mine", false);
      // 후보 수정 모달의 카테고리 선택지. 없으면 "선택 안 함"만 남는다.
      try {
        const cat = await faqApi.getCategories();
        if (!cancelled) setCategories(cat.data?.categories || []);
      } catch {
        /* 카테고리 API가 없거나 실패해도 수정 자체는 가능하다 */
      }
      // 지난 분석을 자동으로 열지 않는다.
      // 이 화면은 새 로그를 올리러 들어오는 자리인데, 전에 돌린 분석이 열려 있으면
      // 업로드 칸에 남의 파일 이름이 붙고 ②③ 탭에도 지난 결과가 차 있어
      // 방금 돌린 것으로 잘못 읽힌다. 지난 결과는 오른쪽 이력에서 골라 연다.
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 백엔드 AnalysisUploadReader가 .csv와 .xlsx를 모두 받고, xlsx는 서버에서 CSV로 변환해
  // AI 서버로 넘긴다. 크기 상한은 Spring multipart 설정(max-file-size=50MB)과 맞춘다.
  const handleFile = (f) => {
    if (!f) return;
    const name = f.name.toLowerCase();
    if (!name.endsWith(".xlsx") && !name.endsWith(".csv")) {
      toast.error("CSV 또는 XLSX 파일만 업로드할 수 있습니다.");
      return;
    }
    if (f.size > MAX_UPLOAD_BYTES) {
      toast.error(`파일이 너무 큽니다. 최대 ${MAX_UPLOAD_MB}MB까지 업로드할 수 있습니다.`);
      return;
    }
    setFile(f);
    setUploadErrors(null);
    // 앞선 업로드 결과가 남아 있으면 "업로드 시작" 버튼이 가려져 다시 올릴 수가 없다.
    // 새 파일을 고른 순간이 새 작업의 시작이므로 앞 결과를 치운다.
    setUploadResult(null);
    setOpenedFileName(null);
  };

  const doUpload = async () => {
    if (!file) return;
    setUploadErrors(null);

    // 백엔드가 같은 범위로 검사해 400을 내므로, 보내기 전에 걸러 분석이 헛돌지 않게 한다
    const cluster = Number(options.minClusterSize);
    const samples = Number(options.minSamples);
    if (options.minClusterSize !== "" && (!Number.isInteger(cluster) || cluster < 2)) {
      toast.error("최소 클러스터 크기는 2 이상의 정수여야 합니다.");
      return;
    }
    if (options.minSamples !== "" && (!Number.isInteger(samples) || samples < 1)) {
      toast.error("최소 샘플 수는 1 이상의 정수여야 합니다.");
      return;
    }

    // 이전 분석의 구독이 남아 있으면 단계 표시가 섞이므로 먼저 정리한다
    activeAnalysisRef.current = null;
    setCancelable(false);
    streamRef.current?.close();
    stopPolling();
    setUploading(true);
    setRunning(true);
    setDone(false);
    setStep(-1);
    setStartedAt(Date.now());
    setElapsed(0);

    try {
      const res = await dashApi.uploadAndAnalyze(file, {
        periodStartDate: options.periodStartDate,
        periodEndDate: options.periodEndDate,
        isMaskingEnabled: options.isMaskingEnabled,
        isTranslationEnabled: options.isTranslationEnabled,
        analysisMode: options.analysisMode,
        clustering: {
          minClusterSize: options.minClusterSize,
          minSamples: options.minSamples,
        },
      });
      setUploading(false);
      setUploadResult(res.data);
      // 분석이 끝나면 file을 비우므로, 이름은 따로 담아 둬야 계속 보인다
      setOpenedFileName(file.name);
      const analysisId = res.data?.analysisId;
      if (!analysisId) throw new Error("서버가 분석 ID를 반환하지 않았습니다.");
      setAnalysisId(analysisId);
      await loadHistory(0, historyScope, onlyCompleted);

      // 같은 파일을 같은 옵션으로 다시 올리면 백엔드가 에러 대신 기존 분석을 돌려준다.
      // 이미 끝난 분석이면 진행 단계를 기다릴 필요 없이 바로 결과를 연다.
      if (res.data?.status === "COMPLETED") {
        if (res.data?.message) toast.info(res.data.message);
        openAnalysis({ analysisId, status: "COMPLETED" });
        return;
      }
      if (res.data?.message) toast.info(res.data.message);

      watchAnalysis(analysisId);
    } catch (err) {
      setUploading(false);
      setRunning(false);
      // 파일 내용 오류는 400 + data { totalErrors, errors[{row, column, reason}] } 로 온다.
      // errors 는 앞의 일부만 오고 totalErrors 가 전체 건수다.
      const report = err?.status === 400 && Array.isArray(err?.data?.errors) ? err.data : null;
      setUploadErrors(report);
      if (!report) toast.error(err.message || "업로드에 실패했습니다.");
    }
  };

  // 후보 목록을 서버에서 다시 받는다. 승인·반려·수정 뒤에 화면 상태만 바꾸지 않고 항상 갱신한다.
  const reloadCandidates = async (id) => {
    if (!id) return;
    try {
      const r = await faqApi.getCandidates(id);
      setCandidates(r.data?.recommendations || []);
    } catch (err) {
      toast.error(err.message || "FAQ 후보를 다시 불러오지 못했습니다.");
    }
  };

  // 승인(POST /faq/apply) — 실제 gold_faq 에 반영된다
  const handleAccept = async (candidateId) => {
    const c = candidates.find((x) => x.candidateId === candidateId);
    if (!c) return;
    if (!analysisId) {
      toast.error("분석 ID를 확인할 수 없어 반영할 수 없습니다.");
      return;
    }
    if (!c.standardQuestion || !c.answerDraft) {
      toast.error("표준 질문 또는 답변 초안이 비어 있는 후보는 반영할 수 없습니다.");
      return;
    }
    // 백엔드 FaqApplyRequest.keywords가 @NotEmpty라 빈 배열은 400이 된다
    if (!c.representativeKeywords?.length) {
      toast.error("대표 키워드가 없는 후보는 반영할 수 없습니다.");
      return;
    }

    setReviewing(candidateId);
    try {
      const res = await faqApi.apply({
        analysisId,
        clusterId: c.clusterId,
        candidateId: c.candidateId,
        qType: c.qType,
        finalQuestion: c.standardQuestion,
        finalAnswer: c.answerDraft,
        keywords: c.representativeKeywords,
        // 후보의 유사어를 그대로 보낸다. 생략해도 서버가 후보 유사어를 쓰지만(FaqService),
        // 보낸 값이 그대로 저장되는 편이 화면에서 본 것과 어긋나지 않는다.
        // 신규는 그대로 저장되고 확장은 기존 FAQ의 유사어에 합쳐진다.
        synonyms: (c.synonyms || []).map((x) => x.text).filter(Boolean),
        isManualPatchConfirmed: false,
      });
      // 백엔드가 EXPAND 후보면 기존 FAQ를 확장하고 editType으로 알려준다
      toast.success(res.data?.editType === "EXPAND"
        ? "기존 FAQ를 확장했습니다."
        : "신규 FAQ로 반영되었습니다.");
      await reloadCandidates(analysisId);
    } catch (err) {
      toast.error(fieldErrors(err) || err.message || "FAQ 반영에 실패했습니다.");
    } finally {
      setReviewing(null);
    }
  };

  // 반려(PATCH .../reject) — 사유는 선택이고, 서버 반영 후 목록을 다시 받는다
  const handleReject = async (candidateId, reason) => {
    setReviewing(candidateId);
    try {
      await faqApi.rejectCandidate(candidateId, reason?.trim() || undefined);
      toast.success("후보를 반려했습니다.");
      setRejectTarget(null);
      await reloadCandidates(analysisId);
    } catch (err) {
      // 대기 상태가 아닌 후보는 409
      toast.error(err?.status === 409
        ? "이미 검토가 끝난 후보입니다."
        : (err.message || "반려에 실패했습니다."));
    } finally {
      setReviewing(null);
    }
  };

  // 후보 수정 저장(PATCH .../{candidateId}) — 승인이 아니라 저장만 한다
  const handleSaveCandidate = async (candidateId, form) => {
    if (!form.standardQuestion.trim() || !form.answerDraft.trim()) {
      toast.error("질문과 답변 초안은 비워 둘 수 없습니다.");
      return;
    }
    setReviewing(candidateId);
    try {
      const res = await faqApi.updateCandidate(candidateId, {
        standardQuestion: form.standardQuestion.trim(),
        answerDraft: form.answerDraft.trim(),
        keywords: form.keywords,
        // 생략하면 서버가 기존 유사어를 유지하므로, 지웠다는 뜻이 전해지도록 항상 보낸다.
        // 이미 있던 글자는 종류(오타·약어)가 유지되고 새 글자는 종류 없이 저장된다.
        synonyms: form.synonyms,
        qType: form.qType,
      });
      // 응답이 후보 항목 하나와 같은 형식이라 그 카드만 교체한다
      if (res.data) {
        setCandidates((prev) => prev.map((x) => x.candidateId === candidateId ? res.data : x));
      } else {
        await reloadCandidates(analysisId);
      }
      toast.success("후보를 저장했습니다.");
      setEditTarget(null);
    } catch (err) {
      // 400이면 칸별 사유가 data에 온다 (예: synonyms[0] "유사어는 100자 이하여야 합니다.")
      const fields = fieldErrors(err);
      toast.error(err?.status === 409
        ? "이미 검토가 끝난 후보는 수정할 수 없습니다."
        : (fields || err.message || "후보 저장에 실패했습니다."));
    } finally {
      setReviewing(null);
    }
  };

  return (
    <div style={{ ...S.page, ...(isMobile ? S.pageMobile : {}) }}>
      <div style={S.pageTitle}>신규 데이터 분석</div>
      <div style={S.pageSub}>미답변 로그를 업로드하고 AI 분석을 실행합니다.</div>

      {/* 탭 */}
      <div style={{ ...S.tabs, ...(isMobile ? S.tabsMobile : {}) }}>
        {[
          { key: "upload",    label: "① 업로드 · 분석" },
          { key: "visual",    label: "② 시각화 · 비교" },
          { key: "recommend", label: `③ FAQ 추천 ${candidates.length > 0 ? `(${candidates.filter(c => c.reviewStatus === "PENDING").length})` : ""}` },
        ].map((t) => (
          <div key={t.key} style={{ ...S.tab, ...(isMobile ? S.tabMobile : {}), ...(tab === t.key ? S.tabActive : {}) }}
            onClick={() => setTab(t.key)}>{t.label}</div>
        ))}
      </div>

      {/* ── 탭 1: 업로드 · 분석 ── */}
      {tab === "upload" && (
        <div style={{ display: "grid", gridTemplateColumns: isMobile ? "1fr" : "1fr 340px", gap: 16 }}>
          <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <div style={S.card}>
              <div style={S.cardHeader}>
                <span style={S.cardTitle}>① 신규 로그 업로드</span>
                <span style={S.badge}>1달 분량</span>
              </div>
              <div style={S.cardBody}>
                {analyzeResult?.clusteringView?.clusterNames && (
                  <div style={{ fontSize: 12, color: "#6B7280", marginBottom: 10 }}>
                    최근 분석 클러스터 <strong style={{ color: "#1565C0" }}>{analyzeResult.clusteringView.clusterNames.length}개</strong>
                  </div>
                )}
                {/* 업로드는 백엔드가 @PreAuthorize("hasRole('ADMIN')")로 막는다 (403) */}
                {!isAdmin && (
                  <div style={S.permissionNotice}>
                    로그 업로드와 분석 실행은 <strong>관리자 계정</strong>만 가능합니다.
                  </div>
                )}
                <input type="file" accept=".xlsx,.csv" style={{ display: "none" }} id="fileInput"
                  disabled={!isAdmin}
                  onChange={(e) => handleFile(e.target.files[0])} />
                <div
                  style={{
                    ...S.dropzone,
                    ...(drag ? S.dropzoneDrag : {}),
                    ...(isAdmin ? {} : S.dropzoneDisabled),
                  }}
                  onClick={() => { if (isAdmin) document.getElementById("fileInput").click(); }}
                  onDragOver={(e) => { if (!isAdmin) return; e.preventDefault(); setDrag(true); }}
                  onDragLeave={() => setDrag(false)}
                  onDrop={(e) => { if (!isAdmin) return; e.preventDefault(); setDrag(false); handleFile(e.dataTransfer.files[0]); }}>
                  <span style={{ display: "inline-flex", alignItems: "center", justifyContent: "center", width: 48, height: 48, borderRadius: 14, background: "#EFF6FF", color: "#1565C0", marginBottom: 12 }}><Icon name="upload" size={22} /></span>
                  <div style={{ fontSize: 14, fontWeight: 500, color: "#374151", marginBottom: 4 }}>
                    {file?.name || openedFileName || "CSV 또는 엑셀 파일을 드래그하거나 클릭하여 선택"}
                  </div>
                  <div style={{ fontSize: 12, color: "#9CA3AF" }}>.csv 또는 .xlsx, 최대 {MAX_UPLOAD_MB}MB</div>
                  <div style={{ fontSize: 12, color: "#6B7280", marginTop: 4 }}>원본 대화 로그와 기존 분석 양식을 그대로 업로드할 수 있습니다.</div>
                </div>
                <div style={S.optionBox}>
                  <div style={S.optionTitle}>분석 옵션</div>

                  {/* 무엇을 묶을지. 전체를 고르면 잘 답한 질문까지 들어가 오래 걸린다. */}
                  <div style={{ ...S.optionRow, ...(isMobile ? S.optionRowMobile : {}) }}>
                    <label style={S.optionLabel}>분석 대상</label>
                    <div style={{ minWidth: 0, ...(isMobile ? S.optionFieldsMobile : {}) }}>
                      <FitSelect style={{ ...S.optionInput, cursor: "pointer" }}
                        value={options.analysisMode} onChange={setOption("analysisMode")}
                        options={MODE_OPTIONS.map((m) => ({ value: m.value, label: m.label }))} />
                      <div style={{ fontSize: 11, color: "#9CA3AF", marginTop: 6, lineHeight: 1.5 }}>
                        {MODE_OPTIONS.find((m) => m.value === options.analysisMode)?.hint}
                      </div>
                    </div>
                  </div>

                  <div style={{ ...S.optionRow, ...(isMobile ? S.optionRowMobile : {}) }}>
                    <label style={S.optionLabel}>분석 기간</label>
                    <div style={{ display: "flex", alignItems: "center", gap: 6, ...(isMobile ? S.optionFieldsMobile : {}) }}>
                      <input type="date" style={{ ...S.optionInput, ...(isMobile ? S.optionInputMobile : {}) }}
                        value={options.periodStartDate} onChange={setOption("periodStartDate")} />
                      <span style={{ color: "#9CA3AF", fontSize: 12 }}>~</span>
                      <input type="date" style={{ ...S.optionInput, ...(isMobile ? S.optionInputMobile : {}) }}
                        value={options.periodEndDate} onChange={setOption("periodEndDate")} />
                    </div>
                  </div>

                  <div style={{ ...S.optionRow, ...(isMobile ? S.optionRowMobile : {}) }}>
                    <label style={S.optionLabel}>전처리</label>
                    <div style={{ display: "flex", gap: 14, flexWrap: "wrap" }}>
                      <label style={S.checkLabel}>
                        <input type="checkbox" checked={options.isMaskingEnabled}
                          onChange={setOption("isMaskingEnabled")} />
                        개인정보 마스킹
                      </label>
                      <label style={S.checkLabel}>
                        <input type="checkbox" checked={options.isTranslationEnabled}
                          onChange={setOption("isTranslationEnabled")} />
                        외국어 번역
                      </label>
                    </div>
                  </div>

                  {/* HDBSCAN은 클러스터 개수를 직접 지정할 수 없어 min_cluster_size로 조절한다.
                      값을 올리면 클러스터가 적고 커지며, 낮추면 많고 작아진다.
                      백엔드가 AI 서버로 그대로 전달하며, 범위를 벗어나면 400이 된다. */}
                  <div style={{ ...S.optionRow, ...(isMobile ? S.optionRowMobile : {}), borderBottom: "none", paddingBottom: 0 }}>
                    <label style={S.optionLabel}>군집화</label>
                    <div style={{ display: "flex", gap: 8, ...(isMobile ? S.optionFieldsMobile : {}) }}>
                      <input type="number" min="2" step="1" placeholder="최소 클러스터 크기 (2 이상)"
                        style={{ ...S.optionInput, ...(isMobile ? S.optionInputMobile : {}) }}
                        value={options.minClusterSize} onChange={setOption("minClusterSize")} />
                      <input type="number" min="1" step="1" placeholder="최소 샘플 수 (1 이상)"
                        style={{ ...S.optionInput, ...(isMobile ? S.optionInputMobile : {}) }}
                        value={options.minSamples} onChange={setOption("minSamples")} />
                    </div>
                  </div>
                  <div style={{ fontSize: 11, color: "#9CA3AF", marginTop: 6, lineHeight: 1.5 }}>
                    클러스터 개수는 HDBSCAN 특성상 직접 지정할 수 없어 최소 크기로 조절합니다.
                    값을 키우면 클러스터가 적고 커집니다. 비워 두면 기본값(5 / 2)을 씁니다.
                    <br />
                    같은 파일이라도 기간·마스킹·군집화·번역 값을 바꾸면 새로 분석합니다.
                  </div>

                </div>

                {file && !uploadResult && (
                  <button style={{ ...S.btnPrimary, marginTop: 12 }}
                    onClick={doUpload} disabled={uploading}>
                    {uploading ? "업로드 중..." : "업로드 시작"}
                  </button>
                )}
                {/* 한 줄이 상태를 따라가야 한다. 분석이 끝난 뒤에도 "지금부터 시작합니다"가
                    남아 있으면 읽는 사람이 어느 단계인지 알 수 없다. */}
                {(uploadResult || openedFileName) && !uploading && (
                  done ? (
                    <div style={S.successBox}>
                      <Icon name="check" size={15} /> 분석 완료! 아래 탭에서 결과를 확인하세요.
                    </div>
                  ) : running ? (
                    <div style={S.runningBox}>
                      분석이 진행 중입니다. 끝나면 결과가 자동으로 열립니다.
                    </div>
                  ) : uploadResult ? (
                    <div style={S.successBox}>
                      <Icon name="check" size={15} /> 파일 업로드 완료! AI 분석을 시작합니다.
                    </div>
                  ) : null
                )}

                {/* 파일 내용 오류 — 400 응답의 data { totalErrors, errors[{row, column, reason}] }.
                    errors 는 앞의 일부만 오므로 나머지는 "외 N건"으로 알린다. */}
                {uploadErrors && (
                  <div style={S.errorBox}>
                    <div style={S.errorHead}>
                      파일 내용에 오류가 {uploadErrors.totalErrors?.toLocaleString()}건 있습니다. 고쳐서 다시 올려주세요.
                    </div>
                    <div style={{ overflowX: "auto", WebkitOverflowScrolling: "touch" }}>
                    <table style={S.errorTable}>
                      <thead>
                        <tr>
                          <th style={{ ...S.errorTh, width: 70 }}>행</th>
                          <th style={{ ...S.errorTh, width: 90 }}>칸</th>
                          <th style={S.errorTh}>사유</th>
                        </tr>
                      </thead>
                      <tbody>
                        {uploadErrors.errors.map((e, i) => (
                          <tr key={i}>
                            {/* 파일 전체에 대한 오류는 row·column 이 비어 온다 */}
                            <td style={S.errorTd}>{e.row ?? "-"}</td>
                            <td style={S.errorTd}>{e.column ?? "-"}</td>
                            <td style={S.errorTd}>{e.reason}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    </div>
                    {uploadErrors.totalErrors > uploadErrors.errors.length && (
                      <div style={S.errorMore}>
                        외 {(uploadErrors.totalErrors - uploadErrors.errors.length).toLocaleString()}건
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* 분석이 시작되기 전에는 보여 줄 것이 없다.
                머리글만 남은 빈 상자를 두면 무언가 비어 있는 것처럼 보인다.
                분석을 시작하는 버튼은 ①의 "업로드 시작" 하나뿐이라 여기서 할 일도 없다. */}
            {(running || done) && (
            <div style={S.card}>
              <div style={S.cardHeader}>
                <span style={S.cardTitle}>② AI 분석 실행</span>
                {done && <span style={{ ...S.badge, background: "#D1FAE5", color: "#065F46" }}>완료</span>}
              </div>
              <div style={S.cardBody}>
                {(running || done) && (
                  <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                    <PhaseList detail={progressDetail} step={step} running={running} done={done} />
                    {running && (
                      <div style={S.runningBox}>
                        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                          <span>
                            분석 진행 중
                            {phaseLabel(progressDetail?.phase) && ` · ${phaseLabel(progressDetail.phase)}`}
                          </span>
                          <span style={{ fontFamily: "monospace" }}>{formatElapsed(elapsed)}</span>
                        </div>

                        {/* AI가 세는 건수. 한쪽이라도 모르면 적지 않는다(계약 4절). */}
                        {unitProgressText(progressDetail) && (
                          <div style={{ marginTop: 8 }}>
                            <div style={{ display: "flex", justifyContent: "space-between", fontSize: 11, marginBottom: 4 }}>
                              <span>{unitProgressText(progressDetail)}</span>
                              {elapsedText(progressDetail) && (
                                <span style={{ opacity: 0.7 }}>AI 경과 {elapsedText(progressDetail)}</span>
                              )}
                            </div>
                            {unitPercent(progressDetail) !== null && (
                              <div style={S.unitBarTrack}>
                                <div style={{ ...S.unitBarFill, width: `${unitPercent(progressDetail)}%` }} />
                              </div>
                            )}
                          </div>
                        )}

                        {/* 진행 정보가 안 오는 것이지 분석이 멈춘 것이 아니다(계약 4절). */}
                        {delayNotice(progressDetail) && (
                          <div style={{ fontSize: 11, marginTop: 8, color: "#B45309" }}>
                            {delayNotice(progressDetail)}
                          </div>
                        )}

                        {/* 건너뛴 단계는 지난 것으로 그려 틈이 생기지 않는다(PhaseList).
                            사용자가 겪지 않는 일을 설명할 필요는 없다. */}
                        <div style={{ fontSize: 11, marginTop: 6, lineHeight: 1.5, opacity: 0.85 }}>
                          {!progressDetail &&
                            "AI 서버가 중간 단계를 보고하지 않아 완료 전까지 단계 표시가 갱신되지 않을 수 있습니다. "}
                          화면을 벗어나도 분석은 계속되며, 돌아오면 이력에서 결과를 확인할 수 있습니다.
                        </div>
                        {/* 잘못 올렸거나 오래 걸릴 때 중간에 멈춘다. 되돌릴 수 없어 한 번 묻는다. */}
                        {cancelable && (
                          <button type="button" style={S.btnCancel}
                            disabled={cancelling}
                            onClick={() => setCancelAsking(true)}>
                            분석 취소
                          </button>
                        )}
                      </div>
                    )}
                    {done && (
                      <div style={{ ...S.successBox, marginTop: 8 }}>
                        <Icon name="check" size={15} /> 분석 완료! <strong>FAQ 추천</strong> 탭에서 결과를 확인하세요.
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
            )}
          </div>

          <div style={S.card}>
            <div style={S.cardHeader}>
              <span style={S.cardTitle}>분석 이력</span>
              <span style={{ fontSize: 11, color: "#9CA3AF" }}>
                {historyInfo.totalCount.toLocaleString()}건
              </span>
            </div>

            <div style={S.historyFilters}>
              {/* scope=mine 이 기본. 전체 분석은 다른 사람이 올린 분석까지 본다. */}
              <div style={S.segment}>
                {[{ key: "mine", label: "내 분석" }, { key: "all", label: "전체 분석" }].map((t) => (
                  <button key={t.key}
                    style={{ ...S.segmentBtn, ...(historyScope === t.key ? S.segmentBtnOn : {}) }}
                    onClick={() => { setHistoryScope(t.key); loadHistory(0, t.key, onlyCompleted); }}>
                    {t.label}
                  </button>
                ))}
              </div>
              <label style={S.checkLabelSm}>
                <input type="checkbox" checked={onlyCompleted}
                  onChange={(e) => { setOnlyCompleted(e.target.checked); loadHistory(0, historyScope, e.target.checked); }} />
                완료만 보기
              </label>
            </div>

            <div style={{ padding: 0 }}>
              {historyLoading && <div style={S.historyEmpty}>불러오는 중...</div>}

              {!historyLoading && history.length === 0 && (
                <div style={S.historyEmpty}>
                  {historyScope === "mine"
                    ? "아직 요청한 분석이 없습니다. 전체 분석을 누르면 다른 사람의 분석을 볼 수 있습니다."
                    : "분석 이력이 없습니다."}
                </div>
              )}

              {!historyLoading && history.map((r) => {
                const isOpen = analysisId === r.analysisId;
                return (
                  <div key={`${r.analysisId}-${r.requestedBy || ""}`}
                    onClick={() => openAnalysis(r)}
                    style={{ ...S.historyRow, ...(isOpen ? S.historyRowOn : {}) }}>
                    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 4 }}>
                      <span style={{ fontFamily: "monospace", color: "#374151" }}>
                        #{r.analysisId}{isOpen && <span style={S.nowTag}>보는 중</span>}
                      </span>
                      <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
                        <span style={{ ...S.statusTag, background: jobStatus(r.status).background, color: jobStatus(r.status).color }}>
                          {jobStatus(r.status).label}
                        </span>
                        {r.isMine && (
                          <button style={S.btnDeleteSm} disabled={deleting === r.analysisId}
                            title="내 이력에서 삭제"
                            onClick={(e) => { e.stopPropagation(); handleDeleteHistory(r); }}>
                            {deleting === r.analysisId ? "…" : <Icon name="close" size={12} strokeWidth={2.2} />}
                          </button>
                        )}
                      </span>
                    </div>

                    <div style={{ color: "#6B7280" }}>{r.fileName}</div>
                    <div style={{ color: "#9CA3AF", marginTop: 2 }}>{r.period}</div>

                    <div style={S.historyMeta}>
                      {historyScope === "all" && r.requestedBy && <span>요청자 {r.requestedBy}</span>}
                      {r.requestedAt && <span>{formatDateTime(r.requestedAt)}</span>}
                    </div>
                    <div style={S.historyMeta}>
                      {r.isMaskingEnabled && <span>마스킹</span>}
                      {r.isTranslationEnabled && <span>번역</span>}
                      {typeof r.minClusterSize === "number" && <span>최소 클러스터 {r.minClusterSize}</span>}
                      {typeof r.minSamples === "number" && <span>최소 샘플 {r.minSamples}</span>}
                    </div>
                  </div>
                );
              })}
            </div>

            {historyInfo.totalPages > 1 && (
              <div style={S.historyPager}>
                <button style={S.pagerBtn} disabled={historyPage === 0}
                  onClick={() => loadHistory(historyPage - 1, historyScope, onlyCompleted)}>이전</button>
                <span style={{ fontSize: 11, color: "#6B7280" }}>
                  {historyPage + 1} / {historyInfo.totalPages}
                </span>
                <button style={S.pagerBtn} disabled={historyPage >= historyInfo.totalPages - 1}
                  onClick={() => loadHistory(historyPage + 1, historyScope, onlyCompleted)}>다음</button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* ── 탭 2: 시각화 ── */}
      {tab === "visual" && (
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          <div style={S.card}>
            <div style={S.cardHeader}>
              <span style={S.cardTitle}>클러스터링 시각화 (UMAP)</span>
              {/* 통계 카드는 전체 기준인데 여기 점은 선별된 질문만이다.
                  무엇을 묶은 그림인지 적지 않으면 숫자가 안 맞는 것으로 보인다. */}
              {analyzeResult && (
                <span style={{ fontSize: 11, color: "#9CA3AF" }}>{scopeCaption(analyzeResult)}</span>
              )}
            </div>
            <div style={S.cardBody}>
              {/* 결과가 아예 없을 때와, 분석은 끝났는데 좌표가 없을 때는 다른 이야기다.
                  뒤쪽은 정상 완료이므로 왜 비었는지 밝힌다. */}
              {!analyzeResult ? (
                <div style={{ textAlign: "center", color: "#9CA3AF", padding: 40 }}>
                  분석을 먼저 실행해주세요.
                </div>
              ) : !(analyzeResult.clusteringView?.points || []).length ? (
                <>
                  <ScopeNotice result={analyzeResult} />
                  <EmptyResult result={analyzeResult} icon="empty" />
                </>
              ) : (
                <>
                  <ScopeNotice result={analyzeResult} />
                  <ClusterScatterPlot
                    points={analyzeResult.clusteringView?.points || []}
                    clusterNames={analyzeResult.clusteringView?.clusterNames || []}
                  />
                </>
              )}
            </div>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: isMobile ? "1fr" : "1fr 1fr", gap: 16 }}>
            <div style={S.card}>
              <div style={S.cardHeader}><span style={S.cardTitle}>응답 유형 비교</span></div>
              <div style={S.cardBody}>
                {analyzeResult ? (() => {
                  // samplingStatus는 정답/오답변/미답변 3분류다. 예전에는 오답변(lowQuality)을
                  // 빼고 그려서 막대 합이 100%가 되지 않았다. 색도 대시보드와 맞춰 둔다.
                  const s = analyzeResult.systemStatus?.samplingStatus;
                  const count = analyzeResult.systemStatus?.totalLogCount || 0;
                  // 0으로 나누지 않으려는 값과 화면에 적는 값은 다르다.
                  // 둘을 같이 쓰면 0건인 분석이 "1건"으로 적힌다.
                  const total = count || 1;
                  const segments = [
                    { label: "정답",      count: s?.correct    || 0, color: "#1565C0" },
                    { label: "오답변",    count: s?.lowQuality || 0, color: "#7C3AED" },
                    { label: "미답변",    count: s?.unanswer   || 0, color: "#EF9F27" },
                  ].map((seg) => ({ ...seg, pct: (seg.count / total) * 100 }));
                  const answeredPct = segments[0].pct.toFixed(1);
                  return (
                    <div>
                      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12, marginBottom: 6 }}>
                        <span style={{ fontWeight: 500 }}>신규 데이터 {count.toLocaleString()}건</span>
                        <span style={{ color: "#1565C0", fontWeight: 600 }}>정답률 {answeredPct}%</span>
                      </div>
                      <div style={{ height: 24, background: "#F3F4F6", borderRadius: 6, overflow: "hidden", display: "flex" }}>
                        {segments.map((seg, i) => (
                          <div
                            key={seg.label}
                            style={{
                              width: `${seg.pct}%`,
                              background: seg.color,
                              transition: "width .6s",
                              borderRadius: i === 0 ? "6px 0 0 6px" : i === segments.length - 1 ? "0 6px 6px 0" : 0,
                            }}
                          />
                        ))}
                      </div>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: "4px 14px", fontSize: 11, color: "#6B7280", marginTop: 8 }}>
                        {segments.map((seg) => (
                          <span key={seg.label} style={{ display: "flex", alignItems: "center", gap: 5 }}>
                            <span style={{ width: 8, height: 8, borderRadius: 2, background: seg.color, flexShrink: 0 }} />
                            {seg.label} {seg.pct.toFixed(1)}%
                            <span style={{ color: "#9CA3AF" }}>({seg.count.toLocaleString()}건)</span>
                          </span>
                        ))}
                      </div>
                    </div>
                  );
                })() : <div style={{ color: "#9CA3AF", fontSize: 12 }}>데이터 없음</div>}
              </div>
            </div>

            <div style={S.card}>
              <div style={S.cardHeader}><span style={S.cardTitle}>분석 결과 요약</span></div>
              <div style={S.cardBody}>
                {[
                  { label: "총 로그 수", value: `${analyzeResult?.systemStatus?.totalLogCount?.toLocaleString() || 0}건`, color: "#111827" },
                  // "예상 해소 건수"·"예상 정확도 향상"은 BE가 저장값으로 재계산해 신뢰할 수 없어 뺐다
                  // (api-spec-changes.md 7절 결정 대기). 확정되면 되살린다.
                  { label: "FAQ 후보 생성 수", value: `${candidates.length}개`, color: "#1565C0" },
                ].map((r) => (
                  <div key={r.label} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "10px 0", borderBottom: "1px solid #F3F4F6", fontSize: 13 }}>
                    <span style={{ color: "#6B7280" }}>{r.label}</span>
                    <strong style={{ color: r.color }}>{r.value}</strong>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── 탭 3: FAQ 추천 ── */}
      {tab === "recommend" && (
        <div>
          {candidates.length === 0 ? (
            <EmptyResult result={analyzeResult} />
          ) : (
            candidates.map((c) => {
              const isOpen = openQuestions.has(c.candidateId);
              // 시각화 결과를 아직 못 받았거나 후보에 군집 번호가 없으면 펼칠 수 없다.
              const canExpand = Boolean(clusterPoints?.length) && typeof c.clusterLabel === "number";
              const labels = c.analysisProvenance?.finalClusterIds || [c.clusterLabel];
              const questions = isOpen ? labels.flatMap(questionsOf) : [];
              return (
              <div key={c.candidateId} style={{ ...S.card, marginBottom: 12, padding: isMobile ? 14 : 18 }}>
                {/* 폰에서는 제목 옆에 버튼 3개가 들어갈 폭이 없어 글자가 세로로 쪼개진다.
                    위아래로 쌓고 버튼은 아래 줄에 모은다. */}
                {/* stretch를 주면 "반려됨" 뱃지가 카드 폭만큼 늘어난다. 왼쪽에 붙여 둔다. */}
                <div style={{ display: "flex", flexDirection: isMobile ? "column" : "row",
                              justifyContent: "space-between", alignItems: "flex-start",
                              gap: isMobile ? 10 : 0, marginBottom: 8 }}>
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 14, fontWeight: 600, color: "#111827", marginBottom: 4 }}>{c.standardQuestion}</div>
                    <div style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 12 }}>
                      {canExpand ? (
                        <>
                          <span style={{ color: "#9CA3AF" }}>매칭 {c.occurrenceCount}건</span>
                          <button type="button" style={S.matchToggle}
                            aria-expanded={isOpen}
                            onClick={() => toggleQuestions(c.candidateId)}>
                            질문 보기 {isOpen ? "▴" : "▾"}
                          </button>
                        </>
                      ) : (
                        <span style={{ color: "#9CA3AF" }}>매칭 {c.occurrenceCount}건</span>
                      )}
                      <MatchedFaqBadge matches={c.matchedFaqs} candidateType={c.candidateType} provenance={c.analysisProvenance} />
                    </div>
                  </div>
                  {/* reviewStatus: PENDING 이면 버튼, REJECTED "반려됨", APPLIED "반영됨" */}
                  {c.reviewStatus === "PENDING" && isAdmin ? (
                    <div style={{ display: "flex", gap: 6 }}>
                      <button style={S.btnAccept} disabled={reviewing === c.candidateId}
                        onClick={() => handleAccept(c.candidateId)}>
                        {reviewing === c.candidateId ? "처리 중..." : "승인"}
                      </button>
                      <button style={S.btnSm} disabled={reviewing === c.candidateId}
                        onClick={() => setEditTarget(c)}>수정</button>
                      <button style={S.btnReject} disabled={reviewing === c.candidateId}
                        onClick={() => { setRejectReason(""); setRejectTarget(c); }}>반려</button>
                    </div>
                  ) : (
                    <StatusChip status={c.reviewStatus} />
                  )}
                </div>
                <div style={{ fontSize: 12, color: "#6B7280", lineHeight: 1.6, marginBottom: 10 }}>{toPlainText(c.answerDraft)}</div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 5 }}>
                  {(c.synonyms || []).map((s, i) => {
                    const kind = SYNONYM_KIND[s.type] || SYNONYM_KIND.SYNONYM;
                    return (
                      <span key={i} style={{
                        fontSize: 11, padding: "2px 8px", borderRadius: 12,
                        background: kind.background, color: kind.color,
                        border: `1px solid ${kind.border}`,
                      }}>
                        {s.text}
                        <span style={{ fontSize: 9, marginLeft: 3, opacity: 0.6 }}>{kind.label}</span>
                      </span>
                    );
                  })}
                </div>

                {/* 이 후보로 묶인 실제 질문들. 목록이 길어도 카드가 늘어나지 않게 안에서 스크롤한다. */}
                {isOpen && (
                  <div style={S.questionBox}>
                    {questions.length === 0 ? (
                      <div style={{ fontSize: 12, color: "#9CA3AF" }}>묶인 질문을 찾지 못했습니다.</div>
                    ) : (
                      <>
                        <div style={S.questionNote}>
                          이 후보가 속한 군집의 질문 {questions.length.toLocaleString()}건입니다.
                          {questions.length !== c.occurrenceCount
                            && " 한 군집에서 후보가 여러 개 나와 매칭 건수와 다릅니다."}
                          {" "}개인정보를 가린 문장이고, 번호는 업로드한 파일의 번호입니다.
                        </div>
                        <div style={S.questionList}>
                          {questions.map((pt) => (
                            <div key={pt.sourceLogSeqNum} style={S.questionRow}>
                              <span style={S.questionNo}>#{pt.sourceLogSeqNum}</span>
                              <span>{pt.text}</span>
                            </div>
                          ))}
                        </div>
                      </>
                    )}
                  </div>
                )}
              </div>
              );
            })
          )}
        </div>
      )}

      {/* 반려 사유 입력 — 본문 { reason }은 선택이라 비워 두고 보내도 된다 */}
      {/* 분석 취소 확인 — 되돌릴 수 없으므로 무엇이 사라지는지 적어 둔다 */}
      {cancelAsking && (
        <div style={S.modalBg} onClick={() => !cancelling && setCancelAsking(false)}>
          <div style={S.modal} onClick={(e) => e.stopPropagation()}>
            <div style={S.modalTitle}>분석을 취소할까요?</div>
            <div style={{ fontSize: 13, color: "#374151", lineHeight: 1.6, marginBottom: 6 }}>
              지금까지 진행한 분석이 버려지고 결과는 남지 않습니다.
              다시 분석하려면 파일을 처음부터 올려야 합니다.
            </div>
            <div style={{ fontSize: 12, color: "#9CA3AF", marginBottom: 14 }}>
              분석 #{analysisId} · {formatElapsed(elapsed)}
            </div>
            <div style={S.modalActions}>
              <button style={S.btnSm} disabled={cancelling}
                onClick={() => setCancelAsking(false)}>계속 진행</button>
              <button style={S.btnReject} disabled={cancelling} onClick={handleCancel}>
                {cancelling ? "취소하는 중..." : "분석 취소"}
              </button>
            </div>
          </div>
        </div>
      )}

      {rejectTarget && (
        <div style={S.modalBg} onClick={() => setRejectTarget(null)}>
          <div style={S.modal} onClick={(e) => e.stopPropagation()}>
            <div style={S.modalTitle}>후보 반려</div>
            <div style={{ fontSize: 13, color: "#374151", marginBottom: 14 }}>
              {rejectTarget.standardQuestion}
            </div>
            <label style={S.modalLabel}>반려 사유 (선택)</label>
            <textarea style={{ ...S.input, height: 80, resize: "vertical", paddingTop: 10 }}
              placeholder="적어 두면 검토 이력에 남습니다"
              value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} />
            <div style={S.modalActions}>
              <button style={S.btnSm} onClick={() => setRejectTarget(null)}>취소</button>
              <button style={S.btnReject} disabled={reviewing === rejectTarget.candidateId}
                onClick={() => handleReject(rejectTarget.candidateId, rejectReason)}>
                {reviewing === rejectTarget.candidateId ? "처리 중..." : "반려"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 후보 수정 — 저장만 하고 승인은 하지 않는다 */}
      {editTarget && (
        <div style={S.modalBg} onClick={() => setEditTarget(null)}>
          <div style={{ ...S.modal, maxWidth: 640 }} onClick={(e) => e.stopPropagation()}>
            <div style={S.modalTitle}>후보 수정</div>
            <EditCandidateForm
              candidate={editTarget}
              categories={categories}
              saving={reviewing === editTarget.candidateId}
              onSave={(form) => handleSaveCandidate(editTarget.candidateId, form)}
              onClose={() => setEditTarget(null)}
            />
          </div>
        </div>
      )}
    </div>
  );
}

// 후보 수정 폼. 질문·답변 초안은 필수이고, 카테고리는 생략하면 기존 값이 유지된다.
function EditCandidateForm({ candidate, categories, saving, onSave, onClose }) {
  const [q, setQ]       = useState(candidate.standardQuestion || "");
  const [a, setA]       = useState(candidate.answerDraft || "");
  const [tags, setTags] = useState((candidate.representativeKeywords || []).join(", "));
  // 후보의 synonyms는 [{ text, type }]이고 저장 요청은 글자 목록이라 text만 쓴다
  const [syns, setSyns] = useState((candidate.synonyms || []).map((x) => x.text).join(", "));
  const [qType, setQType] = useState(candidate.qType ?? "");

  return (
    <div>
      <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: 8 }}>
        <div>
          <label style={S.modalLabel}>표준 질문</label>
          <input style={S.input} value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>답변 초안</label>
          <textarea style={{ ...S.input, height: 140, resize: "vertical", paddingTop: 10 }}
            value={a} onChange={(e) => setA(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>키워드 (쉼표로 구분)</label>
          <input style={S.input} value={tags} onChange={(e) => setTags(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>유사어 (쉼표로 구분)</label>
          <input style={S.input} placeholder="비우면 유사어가 모두 지워집니다"
            value={syns} onChange={(e) => setSyns(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>카테고리</label>
          <select style={{ ...S.input, cursor: "pointer" }} value={qType}
            onChange={(e) => setQType(e.target.value)}>
            <option value="">선택 안 함 (기존 값 유지)</option>
            {(categories || []).map((c) => (
              <option key={c.qType} value={c.qType}>{c.name}</option>
            ))}
          </select>
        </div>
      </div>
      <div style={S.modalActions}>
        <button style={S.btnSm} onClick={onClose}>취소</button>
        <button style={{ ...S.btnAccept, padding: "8px 20px" }} disabled={saving}
          onClick={() => onSave({
            standardQuestion: q,
            answerDraft: a,
            keywords: tags.split(",").map((t) => t.trim()).filter(Boolean),
            synonyms: syns.split(",").map((t) => t.trim()).filter(Boolean),
            qType: qType === "" ? undefined : Number(qType),
          })}>
          {saving ? "저장 중..." : "저장"}
        </button>
      </div>
    </div>
  );
}

const S = {
  errorBox:    { marginTop: 12, border: "1px solid #FECACA", background: "#FEF2F2", borderRadius: 10, padding: 14 },
  errorHead:   { fontSize: 13, fontWeight: 600, color: "#B91C1C", marginBottom: 10 },
  errorTable:  { width: "100%", minWidth: 320, borderCollapse: "collapse", fontSize: 12 },
  errorTh:     { textAlign: "left", padding: "6px 8px", color: "#991B1B", borderBottom: "1px solid #FECACA", fontWeight: 600 },
  errorTd:     { padding: "6px 8px", color: "#374151", borderBottom: "1px solid #FEE2E2", verticalAlign: "top" },
  errorMore:   { marginTop: 8, fontSize: 11, color: "#991B1B" },

  // 후보 반려·수정 모달. 내용이 길어 화면을 넘길 수 있으므로 세로 스크롤을 둔다.
  modalBg:     { position: "fixed", inset: 0, background: "rgba(17,24,39,.45)", display: "flex", alignItems: "center", justifyContent: "center", padding: 20, zIndex: 200 },
  modal:       { width: "100%", maxWidth: 480, maxHeight: "86vh", overflowY: "auto", background: "#fff", borderRadius: 14, padding: 24, boxShadow: "0 10px 40px rgba(0,0,0,.2)" },
  modalTitle:  { fontSize: 16, fontWeight: 700, color: "#111827", marginBottom: 10 },
  modalLabel:  { display: "block", fontSize: 12, fontWeight: 500, color: "#374151", marginBottom: 5 },
  modalActions:{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 18 },
  input:       { width: "100%", minHeight: 40, padding: "9px 12px", border: "1.5px solid #D1D5DB", borderRadius: 8, fontSize: 13, fontFamily: "inherit", outline: "none", boxSizing: "border-box" },
  btnSm:       { padding: "7px 14px", fontSize: 12, border: "1px solid #D1D5DB", borderRadius: 7, background: "#fff", color: "#374151", cursor: "pointer", fontFamily: "inherit", whiteSpace: "nowrap" },

  page:        { padding: 28 },
  pageMobile:  { padding: 16 },
  pageTitle:   { fontSize: 20, fontWeight: 700, color: "#111827", marginBottom: 4 },
  pageSub:     { fontSize: 13, color: "#6B7280", marginBottom: 20 },
  tabs:        { display: "flex", gap: 2, background: "#F3F4F6", borderRadius: 10, padding: 4, width: "fit-content", marginBottom: 20 },
  // 폰에서는 탭 글자가 줄바꿈되며 뭉개져, 폭을 꽉 채우고 넘치면 옆으로 민다
  tabsMobile:  { width: "100%", overflowX: "auto", WebkitOverflowScrolling: "touch" },
  tabMobile:   { whiteSpace: "nowrap", padding: "8px 14px" },
  tab:         { padding: "8px 20px", borderRadius: 8, fontSize: 13, cursor: "pointer", color: "#6B7280" },
  tabActive:   { background: "#fff", color: "#1565C0", fontWeight: 600, boxShadow: "0 1px 3px rgba(0,0,0,.08)" },
  historyFilters: { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8, padding: "10px 16px", borderBottom: "1px solid #F3F4F6", flexWrap: "wrap" },
  segment:     { display: "flex", border: "1px solid #E5E7EB", borderRadius: 8, overflow: "hidden" },
  segmentBtn:  { padding: "5px 12px", fontSize: 11, background: "#fff", border: "none", cursor: "pointer", color: "#6B7280" },
  segmentBtnOn:{ background: "#1565C0", color: "#fff", fontWeight: 600 },
  checkLabelSm:{ display: "flex", alignItems: "center", gap: 5, fontSize: 11, color: "#6B7280", cursor: "pointer" },
  historyEmpty:{ padding: 16, fontSize: 12, color: "#9CA3AF", lineHeight: 1.6 },
  historyRow:  { padding: "12px 13px 12px 16px", borderBottom: "1px solid #F3F4F6", fontSize: 12, cursor: "pointer", transition: "background .12s",
                 borderLeftWidth: 3, borderLeftStyle: "solid", borderLeftColor: "transparent" },
  historyRowOn:{ background: "#EFF6FF", borderLeftColor: "#1565C0" },
  historyMeta: { display: "flex", flexWrap: "wrap", gap: "2px 10px", marginTop: 4, fontSize: 10, color: "#9CA3AF" },
  historyPager:{ display: "flex", alignItems: "center", justifyContent: "center", gap: 10, padding: "10px 16px", borderTop: "1px solid #F3F4F6" },
  pagerBtn:    { padding: "4px 10px", fontSize: 11, border: "1px solid #E5E7EB", borderRadius: 6, background: "#fff", cursor: "pointer" },
  statusTag:   { fontSize: 10, fontWeight: 500, padding: "2px 8px", borderRadius: 10 },
  nowTag:      { fontSize: 9, fontWeight: 700, padding: "1px 6px", borderRadius: 8, background: "#1565C0", color: "#fff", marginLeft: 6 },
  btnDeleteSm: { fontSize: 11, lineHeight: 1, padding: "3px 6px", border: "1px solid #FECACA", borderRadius: 6, background: "#fff", color: "#B91C1C", cursor: "pointer" },
  card:        { background: "#fff", borderRadius: 12, boxShadow: "0 1px 3px rgba(0,0,0,.08)", border: "1px solid rgba(0,0,0,.06)" },
  cardHeader:  { padding: "14px 20px", borderBottom: "1px solid #F3F4F6", display: "flex", alignItems: "center", justifyContent: "space-between" },
  cardTitle:   { fontSize: 14, fontWeight: 600, color: "#111827" },
  cardBody:    { padding: 20 },
  badge:       { fontSize: 11, fontWeight: 500, padding: "2px 9px", borderRadius: 20, background: "#DBEAFE", color: "#1E40AF" },
  dropzone:    { border: "2px dashed #D1D5DB", borderRadius: 12, padding: "32px 20px", textAlign: "center", cursor: "pointer", transition: "all .18s", background: "#fff" },
  optionBox:   { marginTop: 14, padding: "14px 16px", background: "#F9FAFB", borderRadius: 10, border: "1px solid #F3F4F6" },
  optionTitle: { fontSize: 12, fontWeight: 600, color: "#374151", marginBottom: 10 },
  optionRow:   { display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, padding: "8px 0", borderBottom: "1px solid #F3F4F6" },
  // 폰에서는 라벨과 입력이 한 줄에 안 들어가 라벨 글자가 세로로 쪼개진다. 위아래로 쌓는다.
  optionRowMobile: { flexDirection: "column", alignItems: "stretch", gap: 8 },
  optionInputMobile: { width: "100%", minWidth: 0, flex: 1 },
  // 입력 두 개가 나란히 있는 묶음. 폭을 넘지 않게 반반 나눠 준다.
  optionFieldsMobile: { width: "100%", minWidth: 0 },
  optionLabel: { fontSize: 12, color: "#6B7280", display: "flex", alignItems: "center", gap: 6, whiteSpace: "nowrap", flexShrink: 0 },
  optionInput: { height: 32, padding: "0 10px", border: "1px solid #D1D5DB", borderRadius: 6, fontSize: 12, fontFamily: "inherit", outline: "none", boxSizing: "border-box", width: 132 },
  matchToggle: { background: "none", border: "none", padding: 0, fontSize: 12, color: "#1565C0", fontWeight: 500, cursor: "pointer", fontFamily: "inherit" },
  questionBox: { marginTop: 12, paddingTop: 12, borderTop: "1px solid #F3F4F6" },
  questionNote:{ fontSize: 11, color: "#9CA3AF", marginBottom: 8 },
  questionList:{ maxHeight: 260, overflowY: "auto", display: "flex", flexDirection: "column", gap: 6 },
  questionRow: { display: "flex", gap: 8, fontSize: 12, color: "#374151", lineHeight: 1.5 },
  questionNo:  { flexShrink: 0, fontFamily: "monospace", color: "#9CA3AF" },
  checkLabel:  { fontSize: 12, color: "#374151", display: "flex", alignItems: "center", gap: 5, cursor: "pointer", whiteSpace: "nowrap" },
  dropzoneDrag:{ borderColor: "#1565C0", background: "#EFF8FF" },
  dropzoneDisabled: { opacity: 0.5, cursor: "not-allowed", background: "#F9FAFB" },
  permissionNotice: { padding: "10px 12px", background: "#FEF3C7", border: "1px solid #FDE68A", borderRadius: 8, fontSize: 12, color: "#92400E", marginBottom: 10 },
  btnPrimary:  { padding: "10px 20px", background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnAccept:   { padding: "6px 14px", background: "#ECFDF5", color: "#047857", border: "1px solid #A7F3D0", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit", whiteSpace: "nowrap" },
  btnReject:   { padding: "6px 14px", background: "#FFF1F2", color: "#BE123C", border: "1px solid #FECDD3", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit", whiteSpace: "nowrap" },
  // 진행 중 상자 안에 들어간다. 같이 쓰이는 파란 글씨보다 눈에 덜 띄어야
  // 기다리는 것이 기본이고 취소가 예외라는 것이 드러난다.
  // 그림 위에 붙는 설명 줄. 결과를 가리지 않게 낮은 대비로 둔다.
  scopeNotice: { marginBottom: 12, padding: "8px 12px", background: "#F8FAFC", border: "1px solid #E2E8F0", borderRadius: 8, fontSize: 12, color: "#475569", lineHeight: 1.5 },
  // AI가 세는 건수 막대. 화면이 따로 세는 경과 시간과 다른 값이라 작게 둔다.
  unitBarTrack: { height: 4, background: "#DBEAFE", borderRadius: 999, overflow: "hidden" },
  unitBarFill:  { height: "100%", background: "#1D4ED8", borderRadius: 999, transition: "width .4s" },
  btnCancel:   { marginTop: 10, padding: "6px 12px", background: "#fff", color: "#BE123C", border: "1px solid #FECDD3", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  runningBox:  { marginTop: 10, padding: "10px 14px", background: "#EFF8FF", border: "1px solid #BFDBFE", borderRadius: 8, fontSize: 12, color: "#1E40AF" },
  successBox:  { marginTop: 10, padding: "10px 14px", background: "#ECFDF5", border: "1px solid #A7F3D0", borderRadius: 8, fontSize: 13, color: "#047857", display: "flex", alignItems: "center", gap: 8 },
};
