import { useCallback, useEffect, useState } from "react";
import { auditApi } from "../api/audit.js";
import { formatKst } from "../utils/datetime.js";
import { useAuth } from "../hooks/useAuth.js";
import { useToast } from "../hooks/useToast.js";
import { useIsMobile } from "../hooks/useIsMobile.js";

const PAGE_SIZE = 50;

// 백엔드 분류. AUTO(진행 상태 확인·SSE·콜백·토큰 재발급)는 응답에서 항상 빠지므로 선택지에 없다.
const CATEGORIES = [
  { value: "",         label: "전체 분류" },
  { value: "AUTH",     label: "계정·보안" },
  { value: "ANALYSIS", label: "분석" },
  { value: "FAQ",      label: "FAQ" },
  { value: "EXPORT",   label: "데이터 반출" },
  { value: "READ",     label: "조회" },
];

const CATEGORY_STYLE = {
  AUTH:     { background: "#FEF3C7", color: "#92400E" },
  ANALYSIS: { background: "#DBEAFE", color: "#1E40AF" },
  FAQ:      { background: "#EDE9FE", color: "#5B21B6" },
  EXPORT:   { background: "#FFE4E6", color: "#9F1239" },
  READ:     { background: "#F3F4F6", color: "#6B7280" },
};
const CATEGORY_LABEL = Object.fromEntries(CATEGORIES.filter((c) => c.value).map((c) => [c.value, c.label]));

const EMPTY_FILTER = { from: "", to: "", email: "", category: "", result: "", includeReads: false };

export default function AuditLogPage() {
  const isMobile = useIsMobile();
  const [logs, setLogs] = useState([]);
  const [pageInfo, setPageInfo] = useState({ totalCount: 0, totalPages: 0, currentPage: 0 });
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState(EMPTY_FILTER);
  const [openId, setOpenId] = useState(null); // 상세를 펼친 줄
  const { user } = useAuth();
  const toast = useToast();

  // 백엔드가 @PreAuthorize("hasRole('ADMIN')")로 막는다
  const isAdmin = user?.role === "ADMIN";

  const load = useCallback(async (page, f) => {
    setLoading(true);
    try {
      const res = await auditApi.getLogs({ ...f, page, size: PAGE_SIZE });
      setLogs(res.data?.logs || []);
      setPageInfo({
        totalCount: res.data?.totalCount ?? 0,
        totalPages: res.data?.totalPages ?? 0,
        currentPage: res.data?.currentPage ?? page,
      });
      setOpenId(null);
    } catch (err) {
      toast.error(err.message || "감사로그를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    // 비동기 콜백 안에서 상태를 바꿔야 set-state-in-effect 규칙에 걸리지 않는다
    let cancelled = false;
    (async () => {
      if (cancelled) return;
      if (!isAdmin) { setLoading(false); return; }
      await load(0, EMPTY_FILTER);
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin]);

  const setField = (key) => (e) =>
    setFilter((p) => ({ ...p, [key]: e.target.type === "checkbox" ? e.target.checked : e.target.value }));

  // 체크박스·드롭다운은 바꾸는 즉시 다시 조회한다
  const applyNow = (key) => (e) => {
    const value = e.target.type === "checkbox" ? e.target.checked : e.target.value;
    const next = { ...filter, [key]: value };
    setFilter(next);
    load(0, next);
  };

  if (!isAdmin) {
    return (
      <div style={{ ...S.page, ...(isMobile ? S.pageMobile : {}) }}>
        <div style={S.pageTitle}>감사로그</div>
        <div style={S.empty}>관리자만 볼 수 있는 화면입니다.</div>
      </div>
    );
  }

  return (
    <div style={{ ...S.page, ...(isMobile ? S.pageMobile : {}) }}>
      <div style={S.pageTitle}>감사로그</div>
      <div style={S.pageSub}>
        계정·분석·FAQ·데이터 반출 작업과 권한이 막힌 요청을 보여 줍니다.
        목록 열람 같은 단순 조회는 <strong>조회 포함</strong>을 켜면 나옵니다.
      </div>

      <div style={S.filterBar}>
        <input style={S.input} type="date" value={filter.from} onChange={setField("from")} />
        <span style={{ color: "#9CA3AF" }}>~</span>
        <input style={S.input} type="date" value={filter.to} onChange={setField("to")} />
        <input style={{ ...S.input, width: 170 }} placeholder="사용자 (일부)"
          value={filter.email} onChange={setField("email")}
          onKeyDown={(e) => { if (e.key === "Enter") load(0, filter); }} />
        <select style={S.input} value={filter.category} onChange={applyNow("category")}>
          {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
        <label style={S.check}>
          <input type="checkbox" checked={filter.result === "fail"}
            onChange={(e) => {
              const next = { ...filter, result: e.target.checked ? "fail" : "" };
              setFilter(next); load(0, next);
            }} />
          실패만 보기
        </label>
        <label style={S.check}>
          <input type="checkbox" checked={filter.includeReads} onChange={applyNow("includeReads")} />
          조회 포함
        </label>
        <button style={S.btnSearch} onClick={() => load(0, filter)}>검색</button>
        <button style={S.btnSm} onClick={() => { setFilter(EMPTY_FILTER); load(0, EMPTY_FILTER); }}>초기화</button>
        <span style={{ fontSize: 12, color: "#6B7280" }}>
          총 {pageInfo.totalCount.toLocaleString()}건
          {pageInfo.totalPages > 1 && ` · ${pageInfo.currentPage + 1}/${pageInfo.totalPages} 페이지`}
        </span>
      </div>

      {loading ? (
        <div style={S.empty}>불러오는 중...</div>
      ) : logs.length === 0 ? (
        <div style={S.empty}>기록이 없습니다.</div>
      ) : (
        <div style={S.tableWrap}>
          <table style={S.table}>
            <thead>
              <tr>
                {["시각", "사용자", "작업", "대상", "결과", "IP"].map((h) => (
                  <th key={h} style={S.th}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {logs.map((l) => {
                const open = openId === l.logId;
                const ok = l.success !== false;
                return [
                  <tr key={l.logId} style={S.row} onClick={() => setOpenId(open ? null : l.logId)}>
                    <td style={{ ...S.td, whiteSpace: "nowrap" }}>{formatKst(l.createdAt, { withSeconds: true })}</td>
                    <td style={S.td}>{l.email || "-"}</td>
                    <td style={S.td}>
                      {/* action·category 는 BE V9 이후에 온다. 없으면 메서드·경로로 보여 준다. */}
                      {l.category && (
                        <span style={{ ...S.badge, ...(CATEGORY_STYLE[l.category] || {}) }}>
                          {CATEGORY_LABEL[l.category] || l.category}
                        </span>
                      )}{" "}
                      {l.action || <span style={S.mono}>{l.method} {l.path}</span>}
                    </td>
                    <td style={S.td}>{l.target || ""}</td>
                    <td style={S.td}>
                      <span style={{ ...S.chip, ...(ok ? S.chipOk : S.chipNg) }}>
                        {l.resultLabel || (ok ? "성공" : "실패")}
                      </span>
                    </td>
                    <td style={{ ...S.td, whiteSpace: "nowrap" }}>{l.ip || "-"}</td>
                  </tr>,
                  open && (
                    <tr key={`${l.logId}-detail`}>
                      <td style={S.detailCell} colSpan={6}>
                        <div style={S.detailGrid}>
                          {[
                            { k: "메서드", v: l.method },
                            { k: "경로", v: l.path },
                            { k: "쿼리", v: l.query || "-" },
                            { k: "상태 코드", v: l.status },
                            { k: "소요", v: typeof l.durationMs === "number" ? `${l.durationMs}ms` : "-" },
                          ].map((d) => (
                            <div key={d.k}>
                              <div style={S.detailKey}>{d.k}</div>
                              <div style={S.detailVal}>{d.v ?? "-"}</div>
                            </div>
                          ))}
                        </div>
                      </td>
                    </tr>
                  ),
                ];
              })}
            </tbody>
          </table>
        </div>
      )}

      {pageInfo.totalPages > 1 && (
        <div style={S.pager}>
          <button style={S.btnSm} disabled={pageInfo.currentPage === 0}
            onClick={() => load(pageInfo.currentPage - 1, filter)}>← 이전</button>
          <span style={{ fontSize: 12, color: "#6B7280" }}>
            {pageInfo.currentPage + 1} / {pageInfo.totalPages}
          </span>
          <button style={S.btnSm} disabled={pageInfo.currentPage >= pageInfo.totalPages - 1}
            onClick={() => load(pageInfo.currentPage + 1, filter)}>다음 →</button>
        </div>
      )}
    </div>
  );
}

const S = {
  page:       { padding: 28 },
  pageMobile: { padding: 16 },
  pageTitle:  { fontSize: 20, fontWeight: 700, color: "#111827", marginBottom: 4 },
  pageSub:    { fontSize: 13, color: "#6B7280", marginBottom: 20, lineHeight: 1.6 },
  filterBar:  { display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", marginBottom: 16 },
  input:      { height: 36, padding: "0 10px", border: "1.5px solid #D1D5DB", borderRadius: 8, fontSize: 12, fontFamily: "inherit", outline: "none", boxSizing: "border-box", background: "#fff" },
  check:      { display: "flex", alignItems: "center", gap: 5, fontSize: 12, color: "#374151", cursor: "pointer" },
  btnSearch:  { height: 36, padding: "0 18px", background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnSm:      { height: 36, padding: "0 14px", fontSize: 12, border: "1px solid #D1D5DB", borderRadius: 8, background: "#fff", color: "#374151", cursor: "pointer", fontFamily: "inherit" },
  // 이미 overflowX: auto 라 좁은 화면에서도 표가 잘리지 않고 옆으로 밀린다
  tableWrap:  { background: "#fff", borderRadius: 12, border: "1px solid rgba(0,0,0,.06)", boxShadow: "0 1px 3px rgba(0,0,0,.06)", overflowX: "auto", WebkitOverflowScrolling: "touch" },
  table:      { width: "100%", borderCollapse: "collapse", fontSize: 12 },
  th:         { textAlign: "left", padding: "10px 12px", color: "#6B7280", fontWeight: 600, borderBottom: "1px solid #F3F4F6", whiteSpace: "nowrap" },
  row:        { cursor: "pointer" },
  td:         { padding: "9px 12px", color: "#374151", borderBottom: "1px solid #F9FAFB", verticalAlign: "top" },
  mono:       { fontFamily: "monospace", color: "#6B7280", wordBreak: "break-all" },
  badge:      { fontSize: 10, fontWeight: 600, padding: "2px 7px", borderRadius: 9, whiteSpace: "nowrap" },
  chip:       { fontSize: 10, fontWeight: 600, padding: "2px 8px", borderRadius: 10, whiteSpace: "nowrap" },
  chipOk:     { background: "#D1FAE5", color: "#065F46" },
  chipNg:     { background: "#FDECEA", color: "#B91C1C" },
  detailCell: { padding: "10px 12px 14px", background: "#F9FAFB", borderBottom: "1px solid #F3F4F6" },
  detailGrid: { display: "flex", flexWrap: "wrap", gap: "10px 28px" },
  detailKey:  { fontSize: 10, color: "#9CA3AF", marginBottom: 2 },
  detailVal:  { fontSize: 12, color: "#374151", fontFamily: "monospace", wordBreak: "break-all" },
  empty:      { background: "#fff", borderRadius: 12, padding: 48, textAlign: "center", color: "#9CA3AF", fontSize: 14 },
  pager:      { display: "flex", justifyContent: "center", alignItems: "center", gap: 12, marginTop: 16 },
};
