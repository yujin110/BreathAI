import { useState, useEffect, useRef, useCallback } from "react";
import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, Cell, ResponsiveContainer } from "recharts";
import { dashApi } from "../api/dashboard.js";
import { faqApi } from "../api/faq.js";
import MatchedFaqBadge from "../components/MatchedFaqBadge.jsx";
import { isAccepted } from "../utils/reviewStatus.js";
import { toPlainText } from "../utils/text.js";
import { useAuth } from "../hooks/useAuth.js";
import { useToast } from "../hooks/useToast.js";

// Spring의 spring.servlet.multipart.max-file-size=50MB 와 동일하게 맞춘다
const HISTORY_SIZE = 10;

// 백엔드 AnalysisJob.Status 5종에 맞춘 색. 진행 중은 파랑, 완료 초록, 실패 빨강.
function statusStyle(status) {
  if (status === "COMPLETED") return { background: "#D1FAE5", color: "#065F46" };
  if (status === "FAIL")      return { background: "#FDECEA", color: "#B91C1C" };
  return { background: "#DBEAFE", color: "#1E40AF" };
}

// requestedAt은 LocalDateTime(타임존 없음)이라 Date가 로컬로 읽는다. 표시만 쓰므로 그대로 둔다.
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
  const [reviewing, setReviewing]   = useState(null); // 반영 중인 candidateId
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
      if (status === "COMPLETED")     loadResults();
      if (status === "FAIL") {
        setRunning(false);
        toast.error("분석에 실패했습니다.");
      }
      return status === "COMPLETED" || status === "FAIL";
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

  // 특정 분석을 화면으로 불러온다 (시각화 탭 + FAQ 추천 탭)
  const openAnalysis = useCallback(async (item) => {
    if (!item?.analysisId) return;
    const id = item.analysisId;
    setAnalysisId(id);
    setAnalyzeResult(null);
    setCandidates([]);

    if (item.status === "FAIL") {
      setDone(false); setRunning(false); setStep(-1);
      toast.error("실패한 분석입니다. 결과를 불러올 수 없습니다.");
      return;
    }
    if (item.status !== "COMPLETED") {
      // 대기·전처리·분석 중이면 진행 상태를 이어서 보여 준다
      setDone(false); setRunning(true);
      watchAnalysis(id);
      toast.info("분석이 진행 중입니다.");
      return;
    }

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
  };

  const doUpload = async () => {
    if (!file) return;

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
        // 외국어 번역은 화면에서 막아 둔 상태라 항상 false로 보낸다
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
      toast.error(err.message || "업로드에 실패했습니다.");
    }
  };

  // 승인은 POST /api/v1/faq/apply로 실제 gold_faq에 반영해야 한다.
  // (반려는 백엔드 엔드포인트가 없어 화면 상태로만 남고 새로고침하면 사라진다 —
  //  docs/backend-api-requests.md 에 명세 제안)
  const handleCandidate = async (candidateId, action) => {
    const c = candidates.find((x) => x.candidateId === candidateId);
    if (!c) return;

    if (action === "ACCEPTED") {
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
          // clusterLabel은 FaqApplyRequest 명세에 없어 보내지 않는다
          // (백엔드 DTO에는 선택 필드로 있지만 명세 기준으로 맞춘다)
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
      } catch (err) {
        toast.error(err.message || "FAQ 반영에 실패했습니다.");
        return;
      } finally {
        setReviewing(null);
      }
    }

    setCandidates((prev) =>
      prev.map((x) => x.candidateId === candidateId
        ? { ...x, reviewStatus: action === "ACCEPTED" ? "APPLIED" : action }
        : x)
    );
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
                      {/* 외국어 번역은 당분간 선택하지 못하게 막아 둔다.
                          되살릴 때는 disabled와 S.checkLabelDisabled, 아래 안내 문구를 지운다. */}
                      <label style={{ ...S.checkLabel, ...S.checkLabelDisabled }}
                        title="외국어 번역은 현재 사용할 수 없습니다.">
                        <input type="checkbox" disabled checked={false} readOnly
                          style={{ cursor: "not-allowed" }} />
                        외국어 번역
                        <span style={S.pendingTag}>준비 중</span>
                      </label>
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
              {/* scope=mine 이 기본. 전체 보기는 다른 사람의 이력까지 본다. */}
              <div style={S.segment}>
                {[{ key: "mine", label: "내 이력" }, { key: "all", label: "전체 보기" }].map((t) => (
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
                    ? "아직 요청한 분석이 없습니다. 전체 보기를 누르면 다른 사람의 분석을 볼 수 있습니다."
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
                    { label: "정답 (≥75%)",     count: s?.correct    || 0, color: "#1565C0" },
                    { label: "오답변 (25~75%)", count: s?.lowQuality || 0, color: "#7C3AED" },
                    { label: "미답변 (<25%)",   count: s?.unanswer   || 0, color: "#EF9F27" },
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
            candidates.map((c) => (
              <div key={c.candidateId} style={{ ...S.card, marginBottom: 12, padding: 18 }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 8 }}>
                  <div>
                    <div style={{ fontSize: 14, fontWeight: 600, color: "#111827", marginBottom: 4 }}>{c.standardQuestion}</div>
                    <div style={{ display: "flex", gap: 8, alignItems: "center", fontSize: 12 }}>
                      <span style={{ color: "#9CA3AF" }}>매칭 {c.occurrenceCount}건</span>
                      <MatchedFaqBadge matches={c.matchedFaqs} candidateType={c.candidateType} />
                    </div>
                  </div>
                  {c.reviewStatus === "PENDING" && isAdmin ? (
                    <div style={{ display: "flex", gap: 6 }}>
                      <button style={S.btnAccept} disabled={reviewing === c.candidateId}
                        onClick={() => handleCandidate(c.candidateId, "ACCEPTED")}>
                        {reviewing === c.candidateId ? "반영 중..." : "✓ 승인"}
                      </button>
                      <button style={S.btnReject} disabled={reviewing === c.candidateId}
                        onClick={() => handleCandidate(c.candidateId, "REJECTED")}>✗ 반려</button>
                    </div>
                  ) : (
                    <span style={{
                      fontSize: 12, fontWeight: 500, padding: "4px 12px", borderRadius: 20,
                      background: isAccepted(c.reviewStatus) ? "#D1FAE5" : "#FDECEA",
                      color: isAccepted(c.reviewStatus) ? "#065F46" : "#B91C1C",
                    }}>
                      {isAccepted(c.reviewStatus) ? "✅ 승인됨" : "❌ 반려됨"}
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
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}

const S = {
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
  checkLabel:  { fontSize: 12, color: "#374151", display: "flex", alignItems: "center", gap: 5, cursor: "pointer" },
  checkLabelDisabled: { color: "#9CA3AF", cursor: "not-allowed" },
  pendingTag:  { fontSize: 9, fontWeight: 600, padding: "1px 6px", borderRadius: 8, background: "#FEF3C7", color: "#92400E", border: "1px solid #FDE68A" },
  dropzoneDrag:{ borderColor: "#1565C0", background: "#EFF8FF" },
  dropzoneDisabled: { opacity: 0.5, cursor: "not-allowed", background: "#F9FAFB" },
  permissionNotice: { padding: "10px 12px", background: "#FEF3C7", border: "1px solid #FDE68A", borderRadius: 8, fontSize: 12, color: "#92400E", marginBottom: 10 },
  btnPrimary:  { padding: "10px 20px", background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnAccept:   { padding: "6px 14px", background: "#D1FAE5", color: "#065F46", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnReject:   { padding: "6px 14px", background: "#FDECEA", color: "#B91C1C", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  runningBox:  { marginTop: 10, padding: "10px 14px", background: "#EFF8FF", border: "1px solid #BFDBFE", borderRadius: 8, fontSize: 12, color: "#1E40AF" },
  successBox:  { marginTop: 10, padding: "10px 14px", background: "#D1FAE5", borderRadius: 8, fontSize: 13, color: "#065F46" },
};
