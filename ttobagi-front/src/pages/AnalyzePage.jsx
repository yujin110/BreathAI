import { useState, useEffect, useRef, useCallback } from "react";
import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, Cell, ResponsiveContainer } from "recharts";
import { dashApi } from "../api/dashboard.js";
import { faqApi } from "../api/faq.js";
import MatchedFaqBadge from "../components/MatchedFaqBadge.jsx";
import { toPlainText } from "../utils/text.js";
import { useAuth } from "../hooks/useAuth.js";
import { useToast } from "../hooks/useToast.js";

// Spring의 spring.servlet.multipart.max-file-size=50MB 와 동일하게 맞춘다
const HISTORY_SIZE = 10;

// 백엔드 ReviewStatus: PENDING / ACCEPTED / REJECTED / APPLIED
function statusLabel(status) {
  if (status === "REJECTED") return "❌ 반려됨";
  if (status === "APPLIED")  return "✅ 반영됨";
  if (status === "ACCEPTED") return "✅ 승인됨";
  return "대기";
}
function statusChip(status) {
  if (status === "REJECTED") return { background: "#FDECEA", color: "#B91C1C" };
  if (status === "PENDING")  return { background: "#DBEAFE", color: "#1E40AF" };
  return { background: "#D1FAE5", color: "#065F46" };
}

// 백엔드 AnalysisJob.Status 5종에 맞춘 색. 진행 중은 파랑, 완료 초록, 실패 빨강.
function statusStyle(status) {
  if (status === "COMPLETED") return { background: "#D1FAE5", color: "#065F46" };
  if (status === "FAIL")      return { background: "#FDECEA", color: "#B91C1C" };
  return { background: "#DBEAFE", color: "#1E40AF" };
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
              {c.name || `클러스터 ${c.clusterLabel}`}
              <span style={{ color: "#9CA3AF" }}>({(c.size || 0).toLocaleString()})</span>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

export default function AnalyzePage() {
  const [tab, setTab]               = useState("upload");
  const [file, setFile]             = useState(null);
  const [drag, setDrag]             = useState(false);
  const [uploading, setUploading]   = useState(false);
  const [uploadResult, setUploadResult] = useState(null);
  const [uploadErrors, setUploadErrors] = useState(null); // 업로드 400의 행별 오류
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
  const [startedAt, setStartedAt]   = useState(null); // 분석 시작 시각
  const [elapsed, setElapsed]       = useState(0);    // 경과 초
  const [options, setOptions]       = useState({
    periodStartDate: "",
    periodEndDate: "",
    isMaskingEnabled: true,
    isTranslationEnabled: false,
    minClusterSize: "",
    minSamples: "",
  });
  const toast = useToast();
  const { user } = useAuth();

  // SSE 연결과 폴링 타이머를 화면을 떠날 때 반드시 정리한다.
  // 정리하지 않으면 다른 메뉴로 이동한 뒤에도 스트림과 setInterval이 계속 살아남는다.
  const streamRef = useRef(null);
  const pollRef = useRef(null);

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
    streamRef.current?.close();
    stopPolling();
  }, [stopPolling]);

  // 분석 진행 감시. 업로드 직후와, 이력에서 진행 중인 분석을 열었을 때 모두 쓴다.
  // SSE를 먼저 붙이고 끊기면 폴링으로 이어받는다.
  const watchAnalysis = useCallback((id) => {
    streamRef.current?.close();
    stopPolling();

    const loadResults = () => {
      stopPolling();
      setStep(4);
      setDone(true);
      setRunning(false);
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
      if (status === "PENDING")       setStep(-1);
      if (status === "PREPROCESSING") setStep(0);
      if (status === "ANALYZING")     setStep(2);

      if (status !== "COMPLETED" && status !== "FAIL") return false;

      // 종료는 한 번만 처리한다. SSE가 같은 상태를 다시 보내거나
      // 폴링과 겹쳐도 결과 조회·알림이 두 번 나가지 않게 한다.
      if (terminalDoneRef.current === id) return true;
      terminalDoneRef.current = id;

      if (status === "COMPLETED") {
        loadResults();
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
      if (pollRef.current) return;
      pollRef.current = setInterval(async () => {
        try {
          const statusRes = await dashApi.getAnalysisStatus(id);
          if (applyStatus(statusRes.data?.status)) stopPolling();
        } catch (err) {
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
      startPolling  // 종료 상태 없이 스트림이 끊긴 경우 (타임아웃·네트워크 단절)
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

  // 후보에 묶인 질문 목록. 후보의 clusterLabel과 같은 점을 clusteringView.points에서 고른다.
  // AI가 군집별 점 개수와 summaries.size가 어긋나면 분석을 실패시키므로(dashboard_metrics.py)
  // 이렇게 추린 개수는 occurrenceCount와 같다.
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

  const STEPS = ["데이터 전처리", "문장 임베딩", "군집화 (HDBSCAN)", "FAQ 초안 생성", "검증 완료"];

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
    setAnalysisId(id);
    setAnalyzeResult(null);
    setCandidates([]);

    // 목록의 status는 이력을 불러온 시점 값이라, 그 뒤에 끝난 분석도 진행 중으로 남아 있다.
    // COMPLETED·FAIL은 되돌아가지 않으므로 그대로 믿고, 진행 중으로 적혀 있을 때만
    // 서버에 현재 상태를 물어본다. 묻지 않고 "진행 중입니다"를 띄우면
    // 곧이어 SSE가 FAIL을 전해 알림이 겹친다.
    let status = item.status;
    if (status !== "COMPLETED" && status !== "FAIL") {
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
    if (resultRes.status === "fulfilled" && resultRes.value?.data) setAnalyzeResult(resultRes.value.data);
    if (candRes.status === "fulfilled" && candRes.value?.data?.recommendations) {
      setCandidates(candRes.value.data.recommendations);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 내 이력에서만 삭제한다. 분석 결과·후보·반영된 FAQ는 그대로 남는다.
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
      // 완료된 내 분석 중 가장 최근 것을 불러온다. 맨 위가 실패·진행 중이어도 화면이 비지 않는다.
      try {
        const r = await dashApi.getAnalysisHistory({ page: 0, size: 1, scope: "mine", status: "COMPLETED" });
        const latest = r.data?.history?.[0];
        if (!cancelled && latest) openAnalysis(latest);
      } catch {
        /* 완료된 분석이 없으면 빈 화면 안내를 그대로 둔다 */
      }
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
        // 외국어 번역은 AI 점검 중이라 화면에서 막아 두었다. 항상 false로 보낸다.
        isTranslationEnabled: false,
        clustering: {
          minClusterSize: options.minClusterSize,
          minSamples: options.minSamples,
        },
      });
      setUploading(false);
      setUploadResult(res.data);
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
        isManualPatchConfirmed: false,
      });
      // 백엔드가 EXPAND 후보면 기존 FAQ를 확장하고 editType으로 알려준다
      toast.success(res.data?.editType === "EXPAND"
        ? "기존 FAQ를 확장했습니다."
        : "신규 FAQ로 반영되었습니다.");
      await reloadCandidates(analysisId);
    } catch (err) {
      toast.error(err.message || "FAQ 반영에 실패했습니다.");
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
      toast.error(err?.status === 409
        ? "이미 검토가 끝난 후보는 수정할 수 없습니다."
        : (err.message || "후보 저장에 실패했습니다."));
    } finally {
      setReviewing(null);
    }
  };

  return (
    <div style={S.page}>
      <div style={S.pageTitle}>신규 데이터 분석</div>
      <div style={S.pageSub}>미답변 로그를 업로드하고 AI 분석을 실행합니다.</div>

      {/* 탭 */}
      <div style={S.tabs}>
        {[
          { key: "upload",    label: "① 업로드 · 분석" },
          { key: "visual",    label: "② 시각화 · 비교" },
          { key: "recommend", label: `③ FAQ 추천 ${candidates.length > 0 ? `(${candidates.filter(c => c.reviewStatus === "PENDING").length})` : ""}` },
        ].map((t) => (
          <div key={t.key} style={{ ...S.tab, ...(tab === t.key ? S.tabActive : {}) }}
            onClick={() => setTab(t.key)}>{t.label}</div>
        ))}
      </div>

      {/* ── 탭 1: 업로드 · 분석 ── */}
      {tab === "upload" && (
        <div style={{ display: "grid", gridTemplateColumns: "1fr 340px", gap: 16 }}>
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
                  <div style={{ fontSize: 36, marginBottom: 10 }}>📂</div>
                  <div style={{ fontSize: 14, fontWeight: 500, color: "#374151", marginBottom: 4 }}>
                    {file ? file.name : "CSV 또는 엑셀 파일을 드래그하거나 클릭하여 선택"}
                  </div>
                  <div style={{ fontSize: 12, color: "#9CA3AF" }}>.csv 또는 .xlsx, 최대 {MAX_UPLOAD_MB}MB</div>
                </div>
                <div style={S.optionBox}>
                  <div style={S.optionTitle}>분석 옵션</div>

                  <div style={S.optionRow}>
                    <label style={S.optionLabel}>분석 기간</label>
                    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                      <input type="date" style={S.optionInput}
                        value={options.periodStartDate} onChange={setOption("periodStartDate")} />
                      <span style={{ color: "#9CA3AF", fontSize: 12 }}>~</span>
                      <input type="date" style={S.optionInput}
                        value={options.periodEndDate} onChange={setOption("periodEndDate")} />
                    </div>
                  </div>

                  <div style={S.optionRow}>
                    <label style={S.optionLabel}>전처리</label>
                    <div style={{ display: "flex", gap: 14 }}>
                      <label style={S.checkLabel}>
                        <input type="checkbox" checked={options.isMaskingEnabled}
                          onChange={setOption("isMaskingEnabled")} />
                        개인정보 마스킹
                      </label>
                      {/* 2026-10-26 확인: 켜도 번역되지 않는다(AI 쪽 번역 API 호출 실패로 보류 중).
                          되살릴 때는 아래 체크박스를 다시 넣고 전송값(isTranslationEnabled)을
                          options.isTranslationEnabled 로 되돌린다.
                          <label style={S.checkLabel}>
                            <input type="checkbox" checked={options.isTranslationEnabled}
                              onChange={setOption("isTranslationEnabled")} />
                            외국어 번역
                          </label> */}
                    </div>
                  </div>

                  {/* HDBSCAN은 클러스터 개수를 직접 지정할 수 없어 min_cluster_size로 조절한다.
                      값을 올리면 클러스터가 적고 커지며, 낮추면 많고 작아진다.
                      백엔드가 AI 서버로 그대로 전달하며, 범위를 벗어나면 400이 된다. */}
                  <div style={{ ...S.optionRow, borderBottom: "none", paddingBottom: 0 }}>
                    <label style={S.optionLabel}>군집화</label>
                    <div style={{ display: "flex", gap: 8 }}>
                      <input type="number" min="2" step="1" placeholder="최소 클러스터 크기 (2 이상)"
                        style={S.optionInput}
                        value={options.minClusterSize} onChange={setOption("minClusterSize")} />
                      <input type="number" min="1" step="1" placeholder="최소 샘플 수 (1 이상)"
                        style={S.optionInput}
                        value={options.minSamples} onChange={setOption("minSamples")} />
                    </div>
                  </div>
                  <div style={{ fontSize: 11, color: "#9CA3AF", marginTop: 6, lineHeight: 1.5 }}>
                    클러스터 개수는 HDBSCAN 특성상 직접 지정할 수 없어 최소 크기로 조절합니다.
                    값을 키우면 클러스터가 적고 커집니다. 비워 두면 기본값(5 / 2)을 씁니다.
                    <br />
                    같은 파일이라도 기간·마스킹·군집화 값을 바꾸면 새로 분석합니다.
                  </div>

                </div>

                {file && !uploadResult && (
                  <button style={{ ...S.btnPrimary, marginTop: 12 }}
                    onClick={doUpload} disabled={uploading}>
                    {uploading ? "업로드 중..." : "업로드 시작"}
                  </button>
                )}
                {uploadResult && (
                  <div style={S.successBox}>
                    📊 파일 업로드 완료! AI 분석을 시작합니다.
                  </div>
                )}

                {/* 파일 내용 오류 — 400 응답의 data { totalErrors, errors[{row, column, reason}] }.
                    errors 는 앞의 일부만 오므로 나머지는 "외 N건"으로 알린다. */}
                {uploadErrors && (
                  <div style={S.errorBox}>
                    <div style={S.errorHead}>
                      파일 내용에 오류가 {uploadErrors.totalErrors?.toLocaleString()}건 있습니다. 고쳐서 다시 올려주세요.
                    </div>
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
                    {uploadErrors.totalErrors > uploadErrors.errors.length && (
                      <div style={S.errorMore}>
                        외 {(uploadErrors.totalErrors - uploadErrors.errors.length).toLocaleString()}건
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>

            <div style={S.card}>
              <div style={S.cardHeader}>
                <span style={S.cardTitle}>② AI 분석 실행</span>
                {done && <span style={{ ...S.badge, background: "#D1FAE5", color: "#065F46" }}>완료</span>}
              </div>
              <div style={S.cardBody}>
                {!running && !done && (
                  <>
                    <p style={{ fontSize: 13, color: "#6B7280", marginBottom: 16 }}>
                      파일 업로드 시 자동으로 분석이 시작됩니다.
                    </p>
                    <button style={{ ...S.btnPrimary, opacity: 0.5 }} disabled>
                      업로드 후 자동 시작
                    </button>
                  </>
                )}
                {(running || done) && (
                  <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                    {STEPS.map((s, i) => {
                      const isDone = step >= i;
                      const isRun  = step === i && running;
                      return (
                        <div key={s} style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 13 }}>
                          <div style={{
                            width: 24, height: 24, borderRadius: "50%", display: "flex",
                            alignItems: "center", justifyContent: "center", fontSize: 11, fontWeight: 600,
                            background: isDone ? "#D1FAE5" : isRun ? "#DBEAFE" : "#F3F4F6",
                            color: isDone ? "#065F46" : isRun ? "#1D4ED8" : "#9CA3AF",
                          }}>
                            {isDone ? "✓" : i + 1}
                          </div>
                          <span style={{ color: isDone ? "#111827" : isRun ? "#1D4ED8" : "#9CA3AF", fontWeight: isDone || isRun ? 500 : 400 }}>
                            {s}
                          </span>
                          {isRun && <span style={{ fontSize: 11, color: "#1D4ED8" }}>처리 중...</span>}
                        </div>
                      );
                    })}
                    {running && (
                      <div style={S.runningBox}>
                        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                          <span>분석 진행 중</span>
                          <span style={{ fontFamily: "monospace" }}>{formatElapsed(elapsed)}</span>
                        </div>
                        <div style={{ fontSize: 11, marginTop: 6, lineHeight: 1.5, opacity: 0.85 }}>
                          AI 서버가 중간 단계를 보고하지 않아 완료 전까지 위 단계 표시가 갱신되지 않을 수 있습니다.
                          화면을 벗어나도 분석은 계속되며, 돌아오면 이력에서 결과를 확인할 수 있습니다.
                        </div>
                      </div>
                    )}
                    {done && (
                      <div style={{ ...S.successBox, marginTop: 8 }}>
                        🎉 분석 완료! <strong>FAQ 추천</strong> 탭에서 결과를 확인하세요.
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
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
                        <span style={{ ...S.statusTag, ...statusStyle(r.status) }}>{r.status}</span>
                        {r.isMine && (
                          <button style={S.btnDeleteSm} disabled={deleting === r.analysisId}
                            title="내 이력에서 삭제"
                            onClick={(e) => { e.stopPropagation(); handleDeleteHistory(r); }}>
                            {deleting === r.analysisId ? "…" : "✕"}
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
            </div>
            <div style={S.cardBody}>
              {!analyzeResult ? (
                <div style={{ textAlign: "center", color: "#9CA3AF", padding: 40 }}>
                  분석을 먼저 실행해주세요.
                </div>
              ) : (
                <ClusterScatterPlot
                  points={analyzeResult.clusteringView?.points || []}
                  clusterNames={analyzeResult.clusteringView?.clusterNames || []}
                />
              )}
            </div>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
            <div style={S.card}>
              <div style={S.cardHeader}><span style={S.cardTitle}>응답 유형 비교</span></div>
              <div style={S.cardBody}>
                {analyzeResult ? (() => {
                  // samplingStatus는 정답/오답변/미답변 3분류다. 예전에는 오답변(lowQuality)을
                  // 빼고 그려서 막대 합이 100%가 되지 않았다. 색도 대시보드와 맞춰 둔다.
                  const s = analyzeResult.systemStatus?.samplingStatus;
                  const total = analyzeResult.systemStatus?.totalLogCount || 1;
                  const segments = [
                    { label: "정답",      count: s?.correct    || 0, color: "#1565C0" },
                    { label: "오답변",    count: s?.lowQuality || 0, color: "#7C3AED" },
                    { label: "미답변",    count: s?.unanswer   || 0, color: "#EF9F27" },
                  ].map((seg) => ({ ...seg, pct: (seg.count / total) * 100 }));
                  const answeredPct = segments[0].pct.toFixed(1);
                  return (
                    <div>
                      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12, marginBottom: 6 }}>
                        <span style={{ fontWeight: 500 }}>신규 데이터 {total.toLocaleString()}건</span>
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
            <div style={{ ...S.card, ...S.cardBody, textAlign: "center", padding: 48, color: "#9CA3AF" }}>
              <div style={{ fontSize: 36, marginBottom: 10 }}>🔍</div>
              <div>분석을 먼저 실행해주세요.</div>
            </div>
          ) : (
            candidates.map((c) => {
              const isOpen = openQuestions.has(c.candidateId);
              // 시각화 결과를 아직 못 받았거나 후보에 군집 번호가 없으면 펼칠 수 없다.
              const canExpand = Boolean(clusterPoints?.length) && typeof c.clusterLabel === "number";
              const questions = isOpen ? questionsOf(c.clusterLabel) : [];
              return (
              <div key={c.candidateId} style={{ ...S.card, marginBottom: 12, padding: 18 }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 8 }}>
                  <div>
                    <div style={{ fontSize: 14, fontWeight: 600, color: "#111827", marginBottom: 4 }}>{c.standardQuestion}</div>
                    <div style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 12 }}>
                      {canExpand ? (
                        <button type="button" style={S.matchToggle}
                          aria-expanded={isOpen}
                          onClick={() => toggleQuestions(c.candidateId)}>
                          묶인 질문 {c.occurrenceCount}건 {isOpen ? "▴" : "▾"}
                        </button>
                      ) : (
                        <span style={{ color: "#9CA3AF" }}>묶인 질문 {c.occurrenceCount}건</span>
                      )}
                      <MatchedFaqBadge matches={c.matchedFaqs} candidateType={c.candidateType} />
                    </div>
                  </div>
                  {/* reviewStatus: PENDING 이면 버튼, REJECTED "반려됨", APPLIED "반영됨" */}
                  {c.reviewStatus === "PENDING" && isAdmin ? (
                    <div style={{ display: "flex", gap: 6 }}>
                      <button style={S.btnAccept} disabled={reviewing === c.candidateId}
                        onClick={() => handleAccept(c.candidateId)}>
                        {reviewing === c.candidateId ? "처리 중..." : "✓ 승인"}
                      </button>
                      <button style={S.btnSm} disabled={reviewing === c.candidateId}
                        onClick={() => setEditTarget(c)}>수정</button>
                      <button style={S.btnReject} disabled={reviewing === c.candidateId}
                        onClick={() => { setRejectReason(""); setRejectTarget(c); }}>✗ 반려</button>
                    </div>
                  ) : (
                    <span style={{
                      fontSize: 12, fontWeight: 500, padding: "4px 12px", borderRadius: 20,
                      ...statusChip(c.reviewStatus),
                    }}>
                      {statusLabel(c.reviewStatus)}
                    </span>
                  )}
                </div>
                <div style={{ fontSize: 12, color: "#6B7280", lineHeight: 1.6, marginBottom: 10 }}>{toPlainText(c.answerDraft)}</div>
                <div style={{ display: "flex", flexWrap: "wrap", gap: 5 }}>
                  {(c.synonyms || []).map((s, i) => (
                    <span key={i} style={{
                      fontSize: 11, padding: "2px 8px",
                      background: s.type === "TYPO" ? "#FEF3C7" : "#EFF8FF",
                      color: s.type === "TYPO" ? "#92400E" : "#1565C0",
                      borderRadius: 12,
                      border: `1px solid ${s.type === "TYPO" ? "#FDE68A" : "#BFDBFE"}`,
                    }}>
                      {s.text}
                      <span style={{ fontSize: 9, marginLeft: 3, opacity: 0.6 }}>
                        {s.type === "TYPO" ? "오타" : s.type === "ABBR" ? "약어" : "유사어"}
                      </span>
                    </span>
                  ))}
                </div>

                {/* 이 후보로 묶인 실제 질문들. 목록이 길어도 카드가 늘어나지 않게 안에서 스크롤한다. */}
                {isOpen && (
                  <div style={S.questionBox}>
                    {questions.length === 0 ? (
                      <div style={{ fontSize: 12, color: "#9CA3AF" }}>묶인 질문을 찾지 못했습니다.</div>
                    ) : (
                      <>
                        <div style={S.questionNote}>
                          개인정보를 가린 문장입니다. 번호는 업로드한 파일의 번호입니다.
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

// 후보 수정 폼. 질문·답변 초안은 필수이고, 키워드·카테고리는 생략하면 기존 값이 유지된다.
function EditCandidateForm({ candidate, categories, saving, onSave, onClose }) {
  const [q, setQ]       = useState(candidate.standardQuestion || "");
  const [a, setA]       = useState(candidate.answerDraft || "");
  const [tags, setTags] = useState((candidate.representativeKeywords || []).join(", "));
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
  errorTable:  { width: "100%", borderCollapse: "collapse", fontSize: 12 },
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
  btnSm:       { padding: "7px 14px", fontSize: 12, border: "1px solid #D1D5DB", borderRadius: 7, background: "#fff", color: "#374151", cursor: "pointer", fontFamily: "inherit" },

  page:        { padding: 28 },
  pageTitle:   { fontSize: 20, fontWeight: 700, color: "#111827", marginBottom: 4 },
  pageSub:     { fontSize: 13, color: "#6B7280", marginBottom: 20 },
  tabs:        { display: "flex", gap: 2, background: "#F3F4F6", borderRadius: 10, padding: 4, width: "fit-content", marginBottom: 20 },
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
  optionLabel: { fontSize: 12, color: "#6B7280", display: "flex", alignItems: "center", gap: 6 },
  optionInput: { height: 32, padding: "0 10px", border: "1px solid #D1D5DB", borderRadius: 6, fontSize: 12, fontFamily: "inherit", outline: "none", boxSizing: "border-box", width: 132 },
  matchToggle: { background: "none", border: "none", padding: 0, fontSize: 12, color: "#1565C0", fontWeight: 500, cursor: "pointer", fontFamily: "inherit" },
  questionBox: { marginTop: 12, paddingTop: 12, borderTop: "1px solid #F3F4F6" },
  questionNote:{ fontSize: 11, color: "#9CA3AF", marginBottom: 8 },
  questionList:{ maxHeight: 260, overflowY: "auto", display: "flex", flexDirection: "column", gap: 6 },
  questionRow: { display: "flex", gap: 8, fontSize: 12, color: "#374151", lineHeight: 1.5 },
  questionNo:  { flexShrink: 0, fontFamily: "monospace", color: "#9CA3AF" },
  checkLabel:  { fontSize: 12, color: "#374151", display: "flex", alignItems: "center", gap: 5, cursor: "pointer" },
  dropzoneDrag:{ borderColor: "#1565C0", background: "#EFF8FF" },
  dropzoneDisabled: { opacity: 0.5, cursor: "not-allowed", background: "#F9FAFB" },
  permissionNotice: { padding: "10px 12px", background: "#FEF3C7", border: "1px solid #FDE68A", borderRadius: 8, fontSize: 12, color: "#92400E", marginBottom: 10 },
  btnPrimary:  { padding: "10px 20px", background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnAccept:   { padding: "6px 14px", background: "#D1FAE5", color: "#065F46", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnReject:   { padding: "6px 14px", background: "#FDECEA", color: "#B91C1C", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  runningBox:  { marginTop: 10, padding: "10px 14px", background: "#EFF8FF", border: "1px solid #BFDBFE", borderRadius: 8, fontSize: 12, color: "#1E40AF" },
  successBox:  { marginTop: 10, padding: "10px 14px", background: "#D1FAE5", borderRadius: 8, fontSize: 13, color: "#065F46" },
};
