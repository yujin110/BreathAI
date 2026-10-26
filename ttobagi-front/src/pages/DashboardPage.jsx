import { useCallback, useEffect, useState } from "react";
import { BarChart, Bar, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer, CartesianGrid } from "recharts";
import { dashApi } from "../api/dashboard.js";
import { useToast } from "../hooks/useToast.js";

export default function DashboardPage() {
  const [analyze, setAnalyze]   = useState(null);
  const [loading, setLoading]   = useState(true);
  // scope=mine(기본)은 내 이력에 남은 완료 분석, all은 완료된 분석 전체
  const [scope, setScope]       = useState("mine");
  const [notFound, setNotFound] = useState(false);
  // 분석 선택: ""(최신)이면 /analyze/result, 분석 ID면 /analyze/{id}/result 를 쓴다.
  // 두 응답은 최상위 12개 필드가 같아 화면 코드를 그대로 쓸 수 있다.
  const [picked, setPicked]     = useState("");
  const [options, setOptions]   = useState([]);
  const toast = useToast();

  // 선택지는 해당 scope 의 완료된 분석 목록으로 만든다
  const loadOptions = useCallback(async (nextScope) => {
    try {
      const res = await dashApi.getAnalysisHistory({
        page: 0, size: 50, scope: nextScope, status: "COMPLETED",
      });
      setOptions(res.data?.history || []);
    } catch {
      setOptions([]); // 목록을 못 받아도 "최신"으로는 계속 볼 수 있다
    }
  }, []);

  const load = useCallback(async (nextScope, analysisId) => {
    setLoading(true);
    setNotFound(false);
    try {
      const res = analysisId
        ? await dashApi.getFileAnalyzeResult(analysisId)
        : await dashApi.getAnalyzeResult({ scope: nextScope });
      setAnalyze(res.data || null);
    } catch (err) {
      setAnalyze(null);
      // 분석을 한 번도 돌리지 않았으면 404가 온다. 오류가 아니라 정상적인 초기 상태다.
      if (err.status === 404) setNotFound(true);
      else toast.error(err.message || "분석 결과를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // scope 를 바꾸면 선택을 "최신"으로 되돌린다. 다른 scope 의 분석 ID가 남으면 혼란스럽다.
  const changeScope = (next) => {
    setScope(next);
    setPicked("");
    load(next, null);
    loadOptions(next);
  };

  const changePicked = (next) => {
    setPicked(next);
    load(scope, next || null);
  };

  useEffect(() => {
    // 비동기 콜백 안에서 상태를 바꿔야 set-state-in-effect 규칙에 걸리지 않는다
    let cancelled = false;
    (async () => {
      if (cancelled) return;
      await load("mine", null);
      if (!cancelled) loadOptions("mine");
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (loading) return <div style={{ padding: 40, color: "#6B7280" }}>불러오는 중...</div>;

  const system    = analyze?.systemStatus;
  const sampling  = system?.samplingStatus;
  const total     = system?.totalLogCount || 1;

  // 날짜별 미답변·오답변 건수. 명세서 AnalyzeResultResponse.trend 를 쓴다.
  // 백엔드는 usageStats(정답·전체 건수 포함)도 함께 내려주지만 명세서에 없는 필드라 쓰지 않는다.
  const trend = analyze?.trend || [];

  // 현행화 반영 효과 (실증 평가표 5번 "챗봇 반영 효과" — 미답변율 감소율).
  // 명세서 AnalyzeResultResponse 의 performanceMetrics(현재)와 evaluation(반영 후)을 쓴다.
  const metrics = analyze?.performanceMetrics;
  const evaluation = analyze?.evaluation;
  const beforeRate = metrics?.currentUnanswerRate;
  const afterRate  = evaluation?.afterUnanswerRate;
  const hasEffect  = typeof beforeRate === "number" && typeof afterRate === "number";
  // 감소율 = (반영 전 - 반영 후) / 반영 전. 반영 전이 0이면 계산할 수 없다.
  const dropRate = hasEffect && beforeRate > 0
    ? ((beforeRate - afterRate) / beforeRate) * 100
    : null;

  // 급증 키워드. increasedRate가 0인데 surgeDetection에서 같은 키워드가 isNew면
  // 이전 기간에 없던 신규 키워드라 증가율을 계산할 수 없는 경우다 (BE 확인).
  // 이때 "+0%" 대신 "신규"로 표시한다.
  const newKeywords = new Set(
    (analyze?.surgeDetection || []).filter((d) => d.isNew).map((d) => d.keyword)
  );
  const burstKeywords = (analyze?.burstKeywords || []).map((b) => ({
    ...b,
    isNew: newKeywords.has(b.keyword) && !(b.increasedRate > 0),
  }));

  const period = analyze?.periodStartDate && analyze?.periodEndDate
    ? `${analyze.periodStartDate} ~ ${analyze.periodEndDate}`
    : null;

  return (
    <div style={S.page}>
      <div style={S.pageTitle}>대시보드</div>
      <div style={S.pageSub}>
        또타24 챗봇 응답 현황을 확인합니다.
        {analyze?.fileName && <> · 분석 <strong>#{analyze.analysisId}</strong> {analyze.fileName}</>}
      </div>

      <div style={S.scopeRow}>
        <select style={S.pickerSelect} value={picked}
          onChange={(e) => changePicked(e.target.value)}>
          <option value="">최신 분석</option>
          {options.map((o) => (
            <option key={o.analysisId} value={o.analysisId}>
              #{o.analysisId} · {o.fileName}{o.period ? ` · ${o.period}` : ""}
            </option>
          ))}
        </select>
        <div style={S.segment}>
          {[{ key: "mine", label: "내 분석" }, { key: "all", label: "전체 분석" }].map((t) => (
            <button key={t.key}
              style={{ ...S.segmentBtn, ...(scope === t.key ? S.segmentBtnOn : {}) }}
              onClick={() => changeScope(t.key)}>
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {!analyze && (
        <div style={S.notice}>
          {notFound && scope === "mine"
            ? <>아직 완료된 내 분석이 없습니다. <strong>전체 분석</strong>을 눌러 보세요.</>
            : notFound
              ? "분석 결과가 없습니다."
              : <>아직 분석 결과가 없습니다. <strong>신규 데이터 분석</strong>에서 로그를 업로드하면 이곳에 응답 현황이 표시됩니다.</>}
        </div>
      )}

      {/* 통계 카드 */}
      <div style={S.statGrid}>
        {[
          { label: "총 대화 수",      value: (system?.totalLogCount || 0).toLocaleString(),
            sub: period || "분석 대상 파일 기준", color: "#111827" },
          { label: "정답 (≥75%)",     value: `${sampling ? ((sampling.correct / total) * 100).toFixed(1) : 0}%`,          sub: `${(sampling?.correct || 0).toLocaleString()}건`,    color: "#1565C0" },
          { label: "오답변 (25~75%)", value: `${sampling ? ((sampling.lowQuality / total) * 100).toFixed(1) : 0}%`,       sub: `${(sampling?.lowQuality || 0).toLocaleString()}건`, color: "#7C3AED" },
          { label: "미답변 (<25%)",   value: `${sampling ? ((sampling.unanswer / total) * 100).toFixed(1) : 0}%`,       sub: `${(sampling?.unanswer || 0).toLocaleString()}건`, color: "#EF9F27" },
        ].map((s) => (
          <div key={s.label} style={S.statCard}>
            <div style={S.statLabel}>{s.label}</div>
            <div style={{ ...S.statValue, color: s.color }}>{s.value}</div>
            <div style={S.statSub}>{s.sub}</div>
          </div>
        ))}
      </div>

      {/* 날짜별 미답변·오답변 — 명세서 trend */}
      {trend.length > 0 && (
        <div style={{ ...S.card, marginBottom: 20 }}>
          <div style={S.cardHeader}>
            <span style={S.cardTitle}>날짜별 미답변·오답변</span>
            <span style={{ fontSize: 11, color: "#9CA3AF" }}>{trend.length}일</span>
          </div>
          <div style={{ padding: 20, height: 260 }}>
            <ResponsiveContainer>
              <BarChart data={trend} margin={{ top: 4, right: 8, left: -12, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#F3F4F6" vertical={false} />
                <XAxis dataKey="date" tick={{ fontSize: 10, fill: "#9CA3AF" }} tickLine={false} />
                <YAxis tick={{ fontSize: 10, fill: "#9CA3AF" }} tickLine={false} axisLine={false} />
                <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8, border: "1px solid #E5E7EB" }} />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Bar dataKey="lowQualityCount" name="오답변" stackId="a" fill="#7C3AED" />
                <Bar dataKey="unanswerCount"   name="미답변" stackId="a" fill="#EF9F27" radius={[3, 3, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      {/* 현행화 반영 효과 — 실증 평가표 5번 */}
      {(hasEffect || evaluation) && (
        <div style={{ ...S.card, marginBottom: 20 }}>
          <div style={S.cardHeader}>
            <span style={S.cardTitle}>현행화 반영 효과 (예상)</span>
            {evaluation?.beforeStatDate && (
              <span style={{ fontSize: 11, color: "#9CA3AF" }}>기준일 {evaluation.beforeStatDate}</span>
            )}
          </div>

          {hasEffect && (
            <div style={S.effectRow}>
              <div style={S.effectBox}>
                <div style={S.effectLabel}>반영 전 미답변율</div>
                <div style={{ ...S.effectValue, color: "#EF9F27" }}>{beforeRate.toFixed(2)}%</div>
              </div>
              <div style={S.effectArrow}>→</div>
              <div style={S.effectBox}>
                <div style={S.effectLabel}>반영 후 미답변율</div>
                <div style={{ ...S.effectValue, color: "#1565C0" }}>{afterRate.toFixed(2)}%</div>
                {typeof evaluation?.afterUnanswerCount === "number" && (
                  <div style={S.statSub}>{evaluation.afterUnanswerCount.toLocaleString()}건</div>
                )}
              </div>
              {dropRate !== null && (
                <div style={{ ...S.effectBox, background: "#F0FDF4", border: "1px solid #BBF7D0" }}>
                  <div style={S.effectLabel}>미답변율 감소율</div>
                  <div style={{ ...S.effectValue, color: "#166534" }}>{dropRate.toFixed(1)}%</div>
                </div>
              )}
            </div>
          )}

          <div style={S.effectSub}>
            {[
              { label: "AI 해결 건수",   value: evaluation?.resolvedCountByAI ?? metrics?.resolvedCountByAI, unit: "건" },
              { label: "정확도 향상",    value: evaluation?.accuracyGain ?? metrics?.predictedAccuracyGain, unit: "%p" },
              { label: "오탐률",        value: evaluation?.falsePositiveRate, unit: "", ratio: true },
              { label: "평가 임계값",    value: evaluation?.evalThreshold, unit: "", ratio: true },
            ].filter((r) => typeof r.value === "number").map((r) => (
              <span key={r.label} style={S.effectChip}>
                <span style={{ color: "#6B7280" }}>{r.label}</span>
                <strong style={{ color: "#111827" }}>
                  {r.ratio ? r.value.toFixed(3) : r.value.toLocaleString()}{r.unit}
                </strong>
              </span>
            ))}
          </div>
        </div>
      )}

      {/* 급증 키워드 */}
      <div style={S.card}>
        <div style={S.cardHeader}><span style={S.cardTitle}>급증 키워드</span></div>
        <div style={{ padding: 20 }}>
          {burstKeywords.length === 0 && <div style={{ color: "#9CA3AF", fontSize: 12 }}>데이터 없음</div>}
          {burstKeywords.map((b) => (
            <div key={b.keyword} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "7px 0", borderBottom: "1px solid #F3F4F6", fontSize: 13 }}>
              <span style={{ color: "#374151", fontWeight: 500 }}>{b.keyword}</span>
              <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span style={{ color: "#9CA3AF", fontSize: 12 }}>{(b.count ?? 0).toLocaleString()}건</span>
                {b.isNew
                  ? <span style={S.newTag}>신규</span>
                  : <span style={{ color: b.increasedRate >= 0 ? "#E53935" : "#1565C0", fontWeight: 600, fontSize: 12 }}>
                      {b.increasedRate > 0 ? "+" : ""}{(b.increasedRate ?? 0).toFixed(0)}%
                    </span>}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

const S = {
  page:       { padding: 28 },
  pageTitle:  { fontSize: 20, fontWeight: 700, color: "#111827", marginBottom: 4 },
  pageSub:    { fontSize: 13, color: "#6B7280", marginBottom: 24 },
  statGrid:   { display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 14, marginBottom: 20 },
  statCard:   { background: "#fff", borderRadius: 12, padding: "18px 20px", boxShadow: "0 1px 3px rgba(0,0,0,.08)", border: "1px solid rgba(0,0,0,.06)" },
  statLabel:  { fontSize: 12, color: "#6B7280", marginBottom: 6 },
  statValue:  { fontSize: 26, fontWeight: 700, lineHeight: 1 },
  statSub:    { fontSize: 11, color: "#6B7280", marginTop: 4 },
  effectRow:   { display: "flex", alignItems: "center", gap: 14, padding: "20px 20px 4px", flexWrap: "wrap" },
  effectBox:   { flex: "1 1 160px", textAlign: "center", padding: "14px 12px", background: "#F9FAFB", borderRadius: 10, border: "1px solid #F3F4F6" },
  effectLabel: { fontSize: 11, color: "#6B7280", marginBottom: 6 },
  effectValue: { fontSize: 24, fontWeight: 700, lineHeight: 1.1 },
  effectArrow: { fontSize: 18, color: "#9CA3AF", flexShrink: 0 },
  effectSub:   { display: "flex", flexWrap: "wrap", gap: "8px 18px", padding: "12px 20px 18px", fontSize: 12 },
  effectChip:  { display: "flex", alignItems: "center", gap: 6 },
  card:       { background: "#fff", borderRadius: 12, boxShadow: "0 1px 3px rgba(0,0,0,.08)", border: "1px solid rgba(0,0,0,.06)" },
  cardHeader: { padding: "14px 20px", borderBottom: "1px solid #F3F4F6", display: "flex", alignItems: "center", justifyContent: "space-between" },
  cardTitle:  { fontSize: 14, fontWeight: 600, color: "#111827" },
  newTag:     { fontSize: 10, fontWeight: 700, padding: "2px 8px", borderRadius: 10, background: "#DBEAFE", color: "#1E40AF", border: "1px solid #BFDBFE" },
  scopeRow:    { display: "flex", justifyContent: "flex-end", alignItems: "center", gap: 8, marginBottom: 12, flexWrap: "wrap" },
  pickerSelect:{ maxWidth: 420, padding: "6px 10px", fontSize: 12, border: "1px solid #E5E7EB", borderRadius: 8, background: "#fff", color: "#374151", cursor: "pointer" },
  segment:     { display: "flex", border: "1px solid #E5E7EB", borderRadius: 8, overflow: "hidden" },
  segmentBtn:  { padding: "5px 14px", fontSize: 12, background: "#fff", border: "none", cursor: "pointer", color: "#6B7280" },
  segmentBtnOn:{ background: "#1565C0", color: "#fff", fontWeight: 600 },
  notice:     { background: "#EFF8FF", border: "1px solid #BFDBFE", borderRadius: 10, padding: "12px 16px", fontSize: 13, color: "#1E40AF", marginBottom: 20 },
};
