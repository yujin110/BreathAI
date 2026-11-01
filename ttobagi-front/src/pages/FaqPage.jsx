import { useState, useEffect, useCallback } from "react";
import { faqApi } from "../api/faq.js";
import { saveBlob, fieldErrors } from "../api/client.js";
import { formatKst } from "../utils/datetime.js";
import { toPlainText } from "../utils/text.js";
import { useAuth } from "../hooks/useAuth.js";
import { useToast } from "../hooks/useToast.js";
import { useIsMobile } from "../hooks/useIsMobile.js";

const PAGE_SIZE = 10;
// 백엔드 spring.servlet.multipart.max-file-size=50MB 와 같게 맞춘다
const IMPORT_MAX_MB = 50;
const IMPORT_MAX_BYTES = IMPORT_MAX_MB * 1024 * 1024;

// 백엔드 FaqEditHistory.EditType
const EDIT_TYPES = [
  { value: "",       label: "전체" },
  { value: "CREATE", label: "신규 생성" },
  { value: "EXPAND", label: "기존 확장" },
  { value: "MANUAL", label: "수동 수정" },
  { value: "DELETE", label: "삭제" },
];

const EDIT_TYPE_STYLE = {
  CREATE: { background: "#DBEAFE", color: "#1E40AF", border: "#BFDBFE" },
  EXPAND: { background: "#EDE9FE", color: "#5B21B6", border: "#DDD6FE" },
  MANUAL: { background: "#FEF3C7", color: "#92400E", border: "#FDE68A" },
  DELETE: { background: "#FDECEA", color: "#B91C1C", border: "#FECACA" },
};

// 쉼표로 적은 목록을 배열로. 앞뒤 공백과 빈 값을 뺀다.
// 서버도 저장할 때 같은 정리를 하지만(유사어는 대소문자 중복까지 제거),
// 화면에서 먼저 정리해야 보낸 값과 저장된 값이 덜 어긋난다.
function toList(text) {
  return (text || "").split(",").map((t) => t.trim()).filter(Boolean);
}

// 이력의 키워드·유사어 변화. 달라졌을 때만 전후를 나란히 보여 준다.
function ListDiff({ label, before, after }) {
  const a = (before || []).join(", ");
  const b = (after || []).join(", ");
  if (a === b) return null;
  return (
    <div style={{ fontSize: 11, color: "#6B7280", marginTop: 4, display: "flex", gap: 6, flexWrap: "wrap" }}>
      <span style={{ color: "#9CA3AF" }}>{label}</span>
      {a && <span style={S.diffBefore}>{a}</span>}
      {/* 뒤쪽은 비었어도 "(없음)"으로 항상 그리므로, 앞쪽이 있으면 화살표도 always 그린다.
          없으면 "검증용, AAA (없음)"처럼 한 덩어리로 읽힌다. */}
      {a && <span style={{ color: "#9CA3AF" }}>→</span>}
      {b ? <span style={S.diffAfter}>{b}</span> : <span style={{ color: "#9CA3AF" }}>(없음)</span>}
    </div>
  );
}

function EditTypeTag({ editType }) {
  if (!editType) return null;
  const s = EDIT_TYPE_STYLE[editType] || EDIT_TYPE_STYLE.MANUAL;
  const label = EDIT_TYPES.find((t) => t.value === editType)?.label || editType;
  return (
    <span style={{
      fontSize: 10, fontWeight: 600, padding: "2px 8px", borderRadius: 10,
      background: s.background, color: s.color, border: `1px solid ${s.border}`,
    }}>{label}</span>
  );
}

export default function FaqPage() {
  const isMobile = useIsMobile();
  const [tab, setTab]               = useState("list");

  // FAQ 리스트
  const [faqs, setFaqs]             = useState([]);
  const [page, setPage]             = useState(0);
  const [pageInfo, setPageInfo]     = useState({ totalCount: 0, totalPages: 0 });
  const [keywordInput, setKeywordInput]   = useState("");
  const [categoryInput, setCategoryInput] = useState("");
  const [query, setQuery]           = useState({ keyword: "", category: "" });

  // FAQ 후보 추천

  // 전체 변경 이력 탭. FAQ 단건 이력은 카드의 "이력" 버튼(openFaqHistory)에서 faqId로 조회한다.
  const [histories, setHistories]   = useState([]);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyInfo, setHistoryInfo] = useState({ totalCount: 0, totalPages: 0 });
  const [editType, setEditType]     = useState("");
  const [historyLoaded, setHistoryLoaded] = useState(false);


  // 카테고리 선택지 [{ qType, name }]. 필터는 name 을, FAQ 등록 폼은 qType 을 쓴다.
  // 받아오지 못하면 null 로 두고 필터는 문자열 입력으로 떨어진다.
  const [categories, setCategories] = useState(null);

  const [loading, setLoading]       = useState(true);
  const [modal, setModal]           = useState(null);
  // 특정 FAQ의 수정 이력 (GET /faq/history?faqId=)
  const [faqHistory, setFaqHistory] = useState(null);
  const [creating, setCreating]     = useState(false);

  // 키워드 다운로드 기준 버전 선택지. 빈 값이면 "현재 전체"다.
  const [versions, setVersions]           = useState([]);
  const [sinceVersionId, setSinceVersionId] = useState("");
  const [downloadingKeywords, setDownloadingKeywords] = useState(false);

  // FAQ 파일 일괄 업로드
  const [importing, setImporting]       = useState(false);
  const [importResult, setImportResult] = useState(null); // 성공 결과
  const [importErrors, setImportErrors] = useState(null); // 400의 행별 오류

  const { user } = useAuth();
  const toast = useToast();

  // 수정·삭제·반영은 백엔드가 @PreAuthorize("hasRole('ADMIN')")로 막는다.
  // USER 계정에 버튼을 보여주면 누를 때마다 403이 나므로 아예 감춘다.
  const isAdmin = user?.role === "ADMIN";

  const loadFaqs = useCallback(async (targetPage, q) => {
    const res = await faqApi.getList({
      page: targetPage,
      size: PAGE_SIZE,
      keyword: q.keyword,
      category: q.category,
    });
    setFaqs(res.data?.faqList || []);
    setPageInfo({
      totalCount: res.data?.totalCount ?? 0,
      totalPages: res.data?.totalPages ?? 0,
    });
  }, []);

  const loadCategories = useCallback(async () => {
    try {
      const res = await faqApi.getCategories();
      // 문자열 배열로 오는 경우까지 받아 두고, 이름 기준으로 정렬한다
      const list = (res.data?.categories || [])
        .map((c) => (typeof c === "string" ? { qType: undefined, name: c } : c))
        .filter((c) => c?.name);
      list.sort((a, b) => a.name.localeCompare(b.name, "ko"));
      setCategories(list.length ? list : null);
    } catch {
      setCategories(null); // 조회 실패면 필터는 문자열 입력으로 떨어진다
    }
  }, []);

  // 새로 만든 카테고리를 선택지에 넣는다. 이름 순서는 목록을 받을 때와 같게 맞춘다.
  const addCategory = useCallback((created) => {
    if (!created?.name) return;
    setCategories((prev) => {
      const list = [...(prev || []), created];
      list.sort((a, b) => a.name.localeCompare(b.name, "ko"));
      return list;
    });
  }, []);

  // 키워드 다운로드 기준 선택지. 실패해도 "현재 전체"로는 받을 수 있어 조용히 비운다.
  const loadVersions = useCallback(async () => {
    try {
      const res = await faqApi.getVersions(0, 100);
      setVersions(res.data?.versions || []);
    } catch {
      setVersions([]);
    }
  }, []);

  const loadHistories = useCallback(async (targetPage, type) => {
    const res = await faqApi.getHistory({ page: targetPage, size: PAGE_SIZE, editType: type });
    setHistories(res.data?.histories || []);
    setHistoryInfo({
      totalCount: res.data?.totalCount ?? 0,
      totalPages: res.data?.totalPages ?? 0,
    });
  }, []);

  useEffect(() => {
    // FAQ 후보 검토는 "신규 데이터 분석"에서만 하므로 이 화면은 운영 FAQ만 불러온다.
    // 비동기 콜백 안에서 상태를 바꿔야 set-state-in-effect 규칙에 걸리지 않는다.
    let cancelled = false;
    (async () => {
      try {
        await loadFaqs(0, { keyword: "", category: "" });
        // 목록을 띄운 뒤 선택지를 뒤따라 받는다 (없으면 입력창 유지)
        if (!cancelled) loadCategories();
        if (!cancelled) loadVersions();
      } catch (err) {
        if (!cancelled) toast.error(err.message || "FAQ 목록을 불러오지 못했습니다.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const runSearch = async (targetPage, q) => {
    try {
      await loadFaqs(targetPage, q);
      setPage(targetPage);
      setQuery(q);
    } catch (err) {
      toast.error(err.message || "FAQ 목록을 불러오지 못했습니다.");
    }
  };

  const runHistory = async (targetPage, type) => {
    try {
      await loadHistories(targetPage, type);
      setHistoryPage(targetPage);
      setEditType(type);
      setHistoryLoaded(true);
    } catch (err) {
      toast.error(err.message || "변경 이력을 불러오지 못했습니다.");
    }
  };

  const openHistoryTab = () => {
    setTab("history");
    if (!historyLoaded) runHistory(0, "");
  };

  const handleUpdate = async (faq, editReason) => {
    try {
      await faqApi.update(faq.faqId, {
        standardQuestion: faq.standardQuestion,
        answer: faq.answer,
        keywords: faq.keywords,
        // 생략하면 서버가 기존 값을 유지하므로, 비웠다는 뜻이 전해지도록 항상 보낸다
        synonyms: faq.synonyms || [],
        qType: faq.qType,
        editReason: editReason || undefined,
      });
      toast.success("FAQ가 수정되었습니다.");
      setModal(null);
      await runSearch(page, query);
      if (historyLoaded) runHistory(0, editType);
    } catch (err) {
      toast.error(fieldErrors(err) || err.message || "FAQ 수정에 실패했습니다.");
    }
  };

  // 이 FAQ의 수정 이력만 조회한다. 전체 "변경 이력" 탭은 그대로 둔다.
  const openFaqHistory = async (faq) => {
    setModal({ type: "faqHistory", data: faq });
    setFaqHistory(null);
    try {
      const res = await faqApi.getHistory({ page: 0, size: 50, faqId: faq.faqId });
      setFaqHistory(res.data?.histories || []);
    } catch (err) {
      setFaqHistory([]);
      toast.error(err.message || "수정 이력을 불러오지 못했습니다.");
    }
  };

  // FAQ 직접 등록. 질문·답변은 필수이고 카테고리는 GET /faq/categories 드롭다운을 재사용한다.
  const handleCreate = async (form) => {
    if (!form.standardQuestion.trim() || !form.answer.trim()) {
      toast.error("질문과 답변은 비워 둘 수 없습니다.");
      return;
    }
    setCreating(true);
    try {
      const res = await faqApi.create({
        standardQuestion: form.standardQuestion.trim(),
        answer: form.answer.trim(),
        keywords: form.keywords,
        synonyms: form.synonyms,
        qType: form.qType,
      });
      toast.success(`FAQ #${res.data?.faqId}를 등록했습니다.`);
      setModal(null);
      await runSearch(0, query);
      if (historyLoaded) runHistory(0, editType);
    } catch (err) {
      toast.error(fieldErrors(err) || err.message || "FAQ 등록에 실패했습니다.");
    } finally {
      setCreating(false);
    }
  };

  const handleDelete = async (faqId) => {
    if (!confirm("이 FAQ를 삭제하시겠습니까?")) return;
    try {
      await faqApi.remove(faqId);
      toast.success("FAQ가 삭제되었습니다.");
      // 마지막 페이지의 마지막 항목을 지웠으면 빈 페이지가 되니 한 칸 앞으로
      const target = faqs.length === 1 && page > 0 ? page - 1 : page;
      await runSearch(target, query);
      if (historyLoaded) runHistory(0, editType);
    } catch (err) {
      toast.error(err.message || "FAQ 삭제에 실패했습니다.");
    }
  };


  // 키워드만 CSV로 받는다. 기준 버전을 고르면 그 버전 이후 새로 생긴 키워드만 나온다.
  const handleKeywordDownload = async () => {
    setDownloadingKeywords(true);
    try {
      const { blob, fileName } = await faqApi.downloadKeywords(sinceVersionId || undefined);
      saveBlob(blob, fileName);
      toast.success(`${fileName} 다운로드를 시작했습니다.`);
      // FAQ 다운로드와 같은 규칙이라 새 버전(DOWNLOAD)이 생겼을 수 있다
      await loadVersions();
    } catch (err) {
      toast.error(err.message || "키워드 다운로드에 실패했습니다.");
    } finally {
      setDownloadingKeywords(false);
    }
  };

  // FAQ 파일 일괄 업로드. 오류가 1건이라도 있으면 서버가 아무것도 저장하지 않는다.
  const handleImport = async (file) => {
    if (!file) return;
    const name = file.name.toLowerCase();
    if (!name.endsWith(".csv") && !name.endsWith(".xlsx")) {
      toast.error("CSV 또는 엑셀(.xlsx) 파일만 올릴 수 있습니다.");
      return;
    }
    if (file.size > IMPORT_MAX_BYTES) {
      toast.error(`파일이 너무 큽니다. 최대 ${IMPORT_MAX_MB}MB까지 올릴 수 있습니다.`);
      return;
    }
    setImporting(true);
    setImportErrors(null);
    setImportResult(null);
    try {
      const res = await faqApi.importFaq(file);
      // 건너뛴 행이 있으면 서버 안내 문구에 그 건수가 들어 있다
      setImportResult({ ...res.data, message: res.message });
      setModal({ type: "importResult" });
      await runSearch(0, query);
      await loadCategories();
      await loadVersions();
      if (historyLoaded) runHistory(0, editType);
    } catch (err) {
      const report = err?.status === 400 && Array.isArray(err?.data?.errors) ? err.data : null;
      setImportErrors(report);
      if (report) setModal({ type: "importErrors" });
      else toast.error(err.message || "FAQ 파일 업로드에 실패했습니다.");
    } finally {
      setImporting(false);
    }
  };

  if (loading) return <div style={{ padding: 40, color: "#6B7280" }}>불러오는 중...</div>;

  return (
    <div style={{ ...S.page, ...(isMobile ? S.pageMobile : {}) }}>
      <div style={S.pageTitle}>FAQ 관리</div>
      <div style={S.pageSub}>현재 운영 중인 FAQ를 확인하고 수정합니다. 신규 후보 검토는 <strong>신규 데이터 분석</strong>에서 합니다.</div>

      <div style={S.tabs}>
        {[
          { key: "list",      label: `FAQ 리스트 (${pageInfo.totalCount})` },
          { key: "history",   label: "변경 이력" },
        ].map((t) => (
          <div key={t.key}
            style={{ ...S.tab, ...(tab === t.key ? S.tabActive : {}) }}
            onClick={() => (t.key === "history" ? openHistoryTab() : setTab(t.key))}>
            {t.label}
          </div>
        ))}
      </div>

      {/* ── FAQ 리스트 ── */}
      {tab === "list" && (
        <div>
          <div style={{ marginBottom: 14, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            <input style={S.searchInput} placeholder="질문·답변·키워드 검색"
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") runSearch(0, { keyword: keywordInput.trim(), category: categoryInput.trim() }); }} />
            {/* 어느 쪽이든 명세서 GET /api/v1/faq 의 category(표시명 문자열)를 그대로 보낸다.
                선택지를 받아오면 드롭다운, 못 받아오면 입력창이다. */}
            {categories ? (
              <select style={{ ...S.searchInput, width: 180, cursor: "pointer" }}
                value={categoryInput}
                onChange={(e) => {
                  const next = e.target.value;
                  setCategoryInput(next);
                  runSearch(0, { keyword: keywordInput.trim(), category: next });
                }}>
                <option value="">전체 카테고리</option>
                {categories.map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
              </select>
            ) : (
              <input style={{ ...S.searchInput, width: 160 }} placeholder="카테고리명"
                value={categoryInput}
                onChange={(e) => setCategoryInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") runSearch(0, { keyword: keywordInput.trim(), category: categoryInput.trim() }); }} />
            )}
            <button style={S.btnSearch}
              onClick={() => runSearch(0, { keyword: keywordInput.trim(), category: categoryInput.trim() })}>
              검색
            </button>
            {isAdmin && (
              <button style={S.btnCreate} onClick={() => setModal({ type: "create" })}>
                + FAQ 등록
              </button>
            )}
            {(query.keyword || query.category) && (
              <button style={S.btnSm}
                onClick={() => { setKeywordInput(""); setCategoryInput(""); runSearch(0, { keyword: "", category: "" }); }}>
                초기화
              </button>
            )}
            <span style={{ fontSize: 12, color: "#6B7280" }}>
              총 {pageInfo.totalCount.toLocaleString()}개
              {pageInfo.totalPages > 1 && ` · ${page + 1}/${pageInfo.totalPages} 페이지`}
            </span>
          </div>

          {/* 키워드만 내려받기 · FAQ 파일 일괄 업로드 */}
          <div style={S.toolRow}>
            <span style={S.toolLabel}>키워드 다운로드</span>
            <select style={{ ...S.searchInput, width: 220, cursor: "pointer" }}
              value={sinceVersionId} onChange={(e) => setSinceVersionId(e.target.value)}>
              <option value="">현재 전체</option>
              {versions.map((v) => (
                <option key={v.versionId} value={v.versionId}>
                  {v.versionName || `버전 #${v.versionId}`} 이후 새 키워드
                </option>
              ))}
            </select>
            <button style={S.btnSm} disabled={downloadingKeywords} onClick={handleKeywordDownload}>
              {downloadingKeywords ? "내려받는 중..." : "CSV 받기"}
            </button>

            {isAdmin && (
              <>
                <span style={{ ...S.toolLabel, marginLeft: 12 }}>FAQ 파일 업로드</span>
                <input type="file" accept=".csv,.xlsx" id="faqImportInput"
                  style={{ display: "none" }} disabled={importing}
                  onChange={(e) => { handleImport(e.target.files[0]); e.target.value = ""; }} />
                <button style={S.btnCreate} disabled={importing}
                  onClick={() => document.getElementById("faqImportInput").click()}>
                  {importing ? "반영 중..." : "엑셀 업로드"}
                </button>
                {/* 바로 왼쪽이 "키워드 다운로드"라 어느 파일인지 헷갈린다.
                    키워드 CSV에는 답변 열이 없어 올리면 "필수 열이 없습니다"로 거부된다. */}
                <span style={{ fontSize: 11, color: "#9CA3AF" }}>
                  FAQ 버전 관리의 CSV 양식(ID·카테고리·질문·답변·키워드·유사어)으로 올려주세요.
                  .csv·.xlsx, 최대 {IMPORT_MAX_MB}MB
                </span>
              </>
            )}
          </div>

          {faqs.length === 0 ? (
            <div style={S.empty}>
              <div style={{ fontSize: 32, marginBottom: 8 }}>🔍</div>
              <div>{query.keyword || query.category ? "검색 결과가 없습니다." : "등록된 FAQ가 없습니다."}</div>
            </div>
          ) : (
            faqs.map((f) => (
              <div key={f.faqId} style={S.faqCard}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 4 }}>
                      {f.category && <span style={S.categoryTag}>{f.category}</span>}
                      <span style={{ fontSize: 11, fontFamily: "monospace", color: "#C4C8CE" }}>#{f.faqId}</span>
                    </div>
                    <div style={S.faqQ}>{f.standardQuestion}</div>
                    <div style={S.faqA}>{toPlainText(f.answer)}</div>
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 5, marginTop: 8 }}>
                      {f.keywords?.map((k) => <span key={k} style={S.tag}>{k}</span>)}
                    </div>
                    {/* 유사어는 키워드와 쓰임이 달라 구분해 보여 준다 (2026-10-31 추가) */}
                    {f.synonyms?.length > 0 && (
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 5, marginTop: 6, alignItems: "center" }}>
                        <span style={S.tagLabel}>유사어</span>
                        {f.synonyms.map((sy) => <span key={sy} style={S.synTag}>{sy}</span>)}
                      </div>
                    )}
                  </div>
                  <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 8, marginLeft: 16 }}>
                    {f.createdAt && <span style={S.meta}>{f.createdAt} 등록</span>}
                    <div style={{ display: "flex", gap: 6 }}>
                      <button style={S.btnSm} onClick={() => openFaqHistory(f)}>이력</button>
                      {isAdmin && (
                        <>
                          <button style={S.btnSm} onClick={() => setModal({ type: "edit", data: f })}>수정</button>
                          <button style={S.btnSmDanger} onClick={() => handleDelete(f.faqId)}>삭제</button>
                        </>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            ))
          )}

          {pageInfo.totalPages > 1 && (
            <div style={S.pager}>
              <button style={S.btnSm} disabled={page === 0} onClick={() => runSearch(page - 1, query)}>← 이전</button>
              <span style={{ fontSize: 12, color: "#6B7280" }}>{page + 1} / {pageInfo.totalPages}</span>
              <button style={S.btnSm} disabled={page >= pageInfo.totalPages - 1} onClick={() => runSearch(page + 1, query)}>다음 →</button>
            </div>
          )}
        </div>
      )}

      {/* ── 변경 이력 (전역) ── */}
      {tab === "history" && (
        <div>
          <div style={{ marginBottom: 14, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            {EDIT_TYPES.map((t) => (
              <button key={t.value}
                style={{ ...S.filterChip, ...(editType === t.value ? S.filterChipActive : {}) }}
                onClick={() => runHistory(0, t.value)}>
                {t.label}
              </button>
            ))}
            <span style={{ fontSize: 12, color: "#6B7280" }}>
              총 {historyInfo.totalCount.toLocaleString()}건
              {historyInfo.totalPages > 1 && ` · ${historyPage + 1}/${historyInfo.totalPages} 페이지`}
            </span>
          </div>

          {histories.length === 0 ? (
            <div style={S.empty}><div style={{ fontSize: 32, marginBottom: 8 }}>🗒️</div><div>변경 이력이 없습니다.</div></div>
          ) : (
            histories.map((h) => (
              <div key={h.historyId} style={S.faqCard}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8, gap: 8 }}>
                  <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                    <EditTypeTag editType={h.editType} />
                    <span style={{ fontSize: 11, fontFamily: "monospace", color: "#6B7280" }}>FAQ #{h.faqId}</span>
                    {h.versionId
                      ? <span style={S.meta}>버전 #{h.versionId}</span>
                      : <span style={{ ...S.meta, color: "#9CA3AF" }}>버전 미포함</span>}
                    {h.analysisId && <span style={S.meta}>분석 #{h.analysisId}</span>}
                  </div>
                  <span style={{ fontSize: 11, color: "#9CA3AF", whiteSpace: "nowrap" }}>{h.editedBy || "-"}</span>
                </div>

                {h.beforeQuestion && h.beforeQuestion !== h.afterQuestion && (
                  <div style={{ fontSize: 12, marginBottom: 4 }}>
                    <span style={{ color: "#B91C1C", textDecoration: "line-through" }}>{h.beforeQuestion}</span>
                    {" → "}
                    <span style={{ color: "#065F46" }}>{h.afterQuestion}</span>
                  </div>
                )}
                {(!h.beforeQuestion || h.beforeQuestion === h.afterQuestion) && (
                  <div style={S.faqQ}>{h.afterQuestion || h.beforeQuestion}</div>
                )}
                {h.afterAnswer && <div style={S.faqA}>{toPlainText(h.afterAnswer)}</div>}

                <ListDiff label="키워드" before={h.beforeKeywords} after={h.afterKeywords} />
                <ListDiff label="유사어" before={h.beforeSynonyms} after={h.afterSynonyms} />

                {h.editReason && (
                  <div style={{ fontSize: 11, color: "#6B7280", marginTop: 6 }}>사유: {h.editReason}</div>
                )}
                <div style={{ fontSize: 11, color: "#C4C8CE", marginTop: 6 }}>{formatKst(h.createdAt)}</div>
              </div>
            ))
          )}

          {historyInfo.totalPages > 1 && (
            <div style={S.pager}>
              <button style={S.btnSm} disabled={historyPage === 0} onClick={() => runHistory(historyPage - 1, editType)}>← 이전</button>
              <span style={{ fontSize: 12, color: "#6B7280" }}>{historyPage + 1} / {historyInfo.totalPages}</span>
              <button style={S.btnSm} disabled={historyPage >= historyInfo.totalPages - 1} onClick={() => runHistory(historyPage + 1, editType)}>다음 →</button>
            </div>
          )}
        </div>
      )}


      {/* ── 수정 모달 ── */}
      {modal?.type === "faqHistory" && (
        <div style={S.modalBg} onClick={() => setModal(null)}>
          <div style={{ ...S.modal, maxWidth: 680 }} onClick={(e) => e.stopPropagation()}>
            <div style={S.modalTitle}>이 FAQ의 수정 이력</div>
            <div style={{ fontSize: 13, color: "#374151", marginBottom: 4 }}>{modal.data.standardQuestion}</div>
            <div style={{ fontSize: 11, color: "#9CA3AF", marginBottom: 14 }}>#{modal.data.faqId}</div>

            <div style={{ maxHeight: 420, overflowY: "auto" }}>
              {faqHistory === null && <div style={{ fontSize: 12, color: "#9CA3AF" }}>불러오는 중...</div>}
              {faqHistory?.length === 0 && (
                <div style={{ fontSize: 12, color: "#9CA3AF" }}>이 FAQ의 수정 이력이 없습니다.</div>
              )}
              {faqHistory?.map((h) => (
                <div key={h.historyId} style={S.historyItem}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8, marginBottom: 6 }}>
                    <EditTypeTag editType={h.editType} />
                    <span style={{ fontSize: 11, color: "#9CA3AF" }}>
                      {/* 탈퇴한 사용자가 남긴 이력은 editedBy가 null로 온다 (AuthService.deleteMyInfo) */}
                      {h.editedBy || "-"} · {formatKst(h.createdAt)}
                    </span>
                  </div>
                  {h.editReason && (
                    <div style={{ fontSize: 11, color: "#6B7280", marginBottom: 6 }}>사유: {h.editReason}</div>
                  )}
                  {h.beforeQuestion && h.beforeQuestion !== h.afterQuestion && (
                    <div style={S.diffRow}>
                      <span style={S.diffBefore}>{h.beforeQuestion}</span>
                      <span style={{ color: "#9CA3AF" }}>→</span>
                      <span style={S.diffAfter}>{h.afterQuestion}</span>
                    </div>
                  )}
                  <div style={{ fontSize: 12, color: "#374151", lineHeight: 1.6 }}>
                    {toPlainText(h.afterAnswer)}
                  </div>
                  <ListDiff label="키워드" before={h.beforeKeywords} after={h.afterKeywords} />
                  <ListDiff label="유사어" before={h.beforeSynonyms} after={h.afterSynonyms} />
                </div>
              ))}
            </div>

            <div style={S.modalActions}>
              <button style={S.btnSm} onClick={() => setModal(null)}>닫기</button>
            </div>
          </div>
        </div>
      )}

      {/* 일괄 업로드 결과 */}
      {modal?.type === "importResult" && importResult && (
        <div style={S.modalBg} onClick={() => setModal(null)}>
          <div style={{ ...S.modal, maxWidth: 640 }} onClick={(e) => e.stopPropagation()}>
            <div style={S.modalTitle}>FAQ 파일 반영 결과</div>
            {importResult.message && (
              <div style={{ fontSize: 12, color: "#6B7280", marginBottom: 14 }}>{importResult.message}</div>
            )}

            <div style={{ display: "flex", gap: 8, marginBottom: 14, flexWrap: "wrap" }}>
              {[
                { label: "추가", value: importResult.addedCount ?? 0, color: "#1565C0" },
                { label: "수정", value: importResult.updatedCount ?? 0, color: "#92400E" },
                { label: "변경 없음", value: importResult.unchangedCount ?? 0, color: "#6B7280" },
                { label: "건너뜀", value: importResult.skippedCount ?? 0, color: "#B91C1C" },
              ].map((x) => (
                <div key={x.label} style={S.statBox}>
                  <div style={{ fontSize: 11, color: "#6B7280", marginBottom: 4 }}>{x.label}</div>
                  <strong style={{ fontSize: 16, color: x.color }}>{x.value.toLocaleString()}</strong>
                </div>
              ))}
            </div>

            <div style={{ maxHeight: 360, overflowY: "auto" }}>
              {importResult.versionId ? (
                <div style={S.resultLine}>
                  새 버전 <strong>{importResult.versionName || `#${importResult.versionId}`}</strong>이 만들어졌습니다.
                </div>
              ) : (
                <div style={S.resultLine}>바뀐 내용이 없어 버전을 만들지 않았습니다.</div>
              )}

              {importResult.newCategories?.length > 0 && (
                <div style={S.resultLine}>
                  새 카테고리: {importResult.newCategories.join(", ")}
                </div>
              )}

              {importResult.mergedDuplicates?.length > 0 && (
                <div style={S.resultBlock}>
                  <div style={S.resultHead}>같은 내용이라 한 건으로 합친 질문</div>
                  {importResult.mergedDuplicates.map((d, i) => (
                    <div key={i} style={S.resultItem}>
                      <span style={S.rowNo}>{(d.rows || []).join(", ")}행</span> {d.standardQuestion}
                    </div>
                  ))}
                </div>
              )}

              {/* 같은 질문의 FAQ가 여러 개라 어느 쪽을 고칠지 서버가 정하지 못한 행 */}
              {importResult.skipped?.length > 0 && (
                <div style={S.resultBlock}>
                  <div style={{ ...S.resultHead, color: "#B91C1C" }}>
                    반영하지 못한 행 — 담당자가 직접 정해야 합니다
                  </div>
                  {importResult.skipped.map((x, i) => (
                    <div key={i} style={S.resultItem}>
                      <span style={S.rowNo}>{x.row}행</span> {x.standardQuestion}
                      <div style={{ fontSize: 11, color: "#6B7280", marginTop: 2 }}>
                        {x.reason}
                        {x.candidateFaqIds?.length > 0 && ` (후보 FAQ #${x.candidateFaqIds.join(", #")})`}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            <div style={S.modalActions}>
              <button style={S.btnSm} onClick={() => setModal(null)}>닫기</button>
            </div>
          </div>
        </div>
      )}

      {/* 일괄 업로드 오류 — 1건이라도 있으면 아무것도 저장되지 않았다 */}
      {modal?.type === "importErrors" && importErrors && (
        <div style={S.modalBg} onClick={() => setModal(null)}>
          <div style={{ ...S.modal, maxWidth: 680 }} onClick={(e) => e.stopPropagation()}>
            <div style={{ ...S.modalTitle, color: "#B91C1C" }}>파일 내용에 오류가 있습니다</div>
            <div style={{ fontSize: 12, color: "#6B7280", marginBottom: 14 }}>
              오류가 {importErrors.totalErrors?.toLocaleString()}건 있어 아무것도 반영하지 않았습니다.
              파일을 고쳐서 다시 올려주세요.
            </div>
            {/* 좁은 화면에서는 "사유"가 길어 표가 넘치므로 가로로도 밀 수 있게 한다 */}
            <div style={{ maxHeight: 360, overflowY: "auto", overflowX: "auto", WebkitOverflowScrolling: "touch" }}>
              <table style={{ width: "100%", minWidth: 320, borderCollapse: "collapse", fontSize: 12 }}>
                <thead>
                  <tr>
                    {["행", "칸", "사유"].map((h) => (
                      <th key={h} style={S.errTh}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {importErrors.errors.map((e, i) => (
                    <tr key={i}>
                      <td style={S.errTd}>{e.row}</td>
                      <td style={S.errTd}>{e.column}</td>
                      <td style={S.errTd}>{e.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {importErrors.totalErrors > importErrors.errors.length && (
                <div style={{ fontSize: 11, color: "#9CA3AF", marginTop: 8 }}>
                  외 {(importErrors.totalErrors - importErrors.errors.length).toLocaleString()}건
                </div>
              )}
            </div>
            <div style={S.modalActions}>
              <button style={S.btnSm} onClick={() => setModal(null)}>닫기</button>
            </div>
          </div>
        </div>
      )}

      {modal?.type === "create" && (
        <div style={S.modalBg} onClick={() => setModal(null)}>
          <div style={S.modal} onClick={(e) => e.stopPropagation()}>
            <div style={S.modalTitle}>FAQ 등록</div>
            <CreateFaqForm
              categories={categories}
              isAdmin={isAdmin}
              onCategoryCreated={addCategory}
              saving={creating}
              onSave={handleCreate}
              onClose={() => setModal(null)}
            />
          </div>
        </div>
      )}

      {modal?.type === "edit" && (
        <div style={S.modalBg} onClick={() => setModal(null)}>
          <div style={S.modal} onClick={(e) => e.stopPropagation()}>
            <div style={S.modalTitle}>FAQ 수정</div>
            <EditFaqForm faq={modal.data} categories={categories} isAdmin={isAdmin}
              onCategoryCreated={addCategory}
              onSave={handleUpdate} onClose={() => setModal(null)} />
          </div>
        </div>
      )}
    </div>
  );
}

// 카테고리 선택칸. 관리자는 맨 아래 "+ 새 카테고리"로 바로 만들 수 있다.
// 만들면 선택지에 넣고 곧바로 선택된 상태로 둔다.
function CategorySelect({ categories, value, onChange, isAdmin, onCreated, emptyLabel }) {
  const [adding, setAdding] = useState(false);
  const [name, setName]     = useState("");
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed) { toast.error("카테고리 이름을 입력해주세요."); return; }
    setSaving(true);
    try {
      const created = (await faqApi.createCategory(trimmed)).data;
      onCreated(created);
      onChange(String(created.qType));
      setAdding(false);
      setName("");
      toast.success(`카테고리 "${created.name}"을 만들었습니다.`);
    } catch (err) {
      // 공백을 모두 뺀 이름이 이미 있으면 409다 ("열차 운영관련" = "열차운영관련").
      // 비었거나 100자를 넘으면 400이고 사유가 data.name에 온다.
      const fields = fieldErrors(err);
      toast.error(err?.status === 409
        ? "이미 있는 카테고리입니다. 목록에서 골라 주세요."
        : (fields || err.message || "카테고리를 만들지 못했습니다."));
    } finally {
      setSaving(false);
    }
  };

  if (adding) {
    return (
      <div style={{ display: "flex", gap: 6 }}>
        <input style={S.input} placeholder="새 카테고리 이름 (100자 이하)" autoFocus
          value={name} onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") submit(); }} />
        <button style={{ ...S.btnSm, whiteSpace: "nowrap" }} disabled={saving} onClick={submit}>
          {saving ? "만드는 중..." : "만들기"}
        </button>
        <button style={{ ...S.btnSm, whiteSpace: "nowrap" }}
          onClick={() => { setAdding(false); setName(""); }}>취소</button>
      </div>
    );
  }

  return (
    <select style={{ ...S.input, cursor: "pointer" }} value={value}
      onChange={(e) => {
        if (e.target.value === "__new__") { setAdding(true); return; }
        onChange(e.target.value);
      }}>
      <option value="">{emptyLabel}</option>
      {(categories || []).map((c) => (
        <option key={c.qType} value={c.qType}>{c.name}</option>
      ))}
      {isAdmin && <option value="__new__">+ 새 카테고리</option>}
    </select>
  );
}

// FAQ 직접 등록 폼. 질문·답변은 필수, 카테고리는 선택(생략하면 서버가 null 로 둔다).
function CreateFaqForm({ categories, isAdmin, onCategoryCreated, saving, onSave, onClose }) {
  const [q, setQ]       = useState("");
  const [a, setA]       = useState("");
  const [tags, setTags] = useState("");
  const [syns, setSyns] = useState("");
  const [qType, setQType] = useState("");

  return (
    <div>
      <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: 8 }}>
        <div>
          <label style={S.modalLabel}>표준 질문</label>
          <input style={S.input} placeholder="예: 지하철 막차 시간이 언제인가요?"
            value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>답변</label>
          <textarea style={{ ...S.input, height: 140, resize: "vertical", paddingTop: 10 }}
            value={a} onChange={(e) => setA(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>키워드 (쉼표로 구분)</label>
          <input style={S.input} placeholder="막차, 첫차, 시간"
            value={tags} onChange={(e) => setTags(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>유사어 (쉼표로 구분)</label>
          <input style={S.input} placeholder="막차시간, 마지막 열차"
            value={syns} onChange={(e) => setSyns(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>카테고리</label>
          <CategorySelect categories={categories} value={qType} onChange={setQType}
            isAdmin={isAdmin} onCreated={onCategoryCreated} emptyLabel="선택 안 함" />
        </div>
      </div>
      <div style={S.modalActions}>
        <button style={S.btnSm} onClick={onClose}>취소</button>
        <button style={{ ...S.btnAccept, padding: "8px 20px" }} disabled={saving}
          onClick={() => onSave({
            standardQuestion: q,
            answer: a,
            keywords: tags.split(",").map((t) => t.trim()).filter(Boolean),
            synonyms: toList(syns),
            qType: qType === "" ? undefined : Number(qType),
          })}>
          {saving ? "등록 중..." : "등록"}
        </button>
      </div>
    </div>
  );
}

function EditFaqForm({ faq, categories, isAdmin, onCategoryCreated, onSave, onClose }) {
  const [q, setQ]       = useState(faq.standardQuestion || "");
  const [a, setA]       = useState(faq.answer || "");
  const [tags, setTags] = useState(faq.keywords?.join(", ") || "");
  const [syns, setSyns] = useState(faq.synonyms?.join(", ") || "");
  const [qType, setQType] = useState(faq.qType == null ? "" : String(faq.qType));
  const [reason, setReason] = useState("");

  return (
    <div>
      <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: 8 }}>
        <div>
          <label style={S.modalLabel}>표준 질문</label>
          <input style={S.input} value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div>
          <label style={S.modalLabel}>답변</label>
          <textarea style={{ ...S.input, height: 100, resize: "vertical", paddingTop: 10 }}
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
          <CategorySelect categories={categories} value={qType} onChange={setQType}
            isAdmin={isAdmin} onCreated={onCategoryCreated} emptyLabel="선택 안 함 (기존 값 유지)" />
        </div>
        <div>
          <label style={S.modalLabel}>수정 사유 (선택)</label>
          <input style={S.input} placeholder="변경 이력에 함께 기록됩니다"
            value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
      </div>
      <div style={S.modalActions}>
        <button style={S.btnSm} onClick={onClose}>취소</button>
        <button style={{ ...S.btnAccept, padding: "8px 20px" }} onClick={() =>
          onSave(
            {
              ...faq,
              standardQuestion: q,
              answer: a,
              keywords: tags.split(",").map((t) => t.trim()).filter(Boolean),
              synonyms: toList(syns),
              // 비워 두면 보내지 않아 기존 카테고리가 그대로 남는다
              qType: qType === "" ? undefined : Number(qType),
            },
            reason.trim()
          )
        }>저장</button>
      </div>
    </div>
  );
}

const S = {
  toolRow:     { marginBottom: 14, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", paddingTop: 12, borderTop: "1px solid #F3F4F6" },
  toolLabel:   { fontSize: 12, fontWeight: 600, color: "#374151" },
  statBox:     { flex: 1, minWidth: 96, background: "#F9FAFB", borderRadius: 8, padding: "10px 12px", textAlign: "center" },
  resultLine:  { fontSize: 12, color: "#374151", marginBottom: 6 },
  resultBlock: { marginTop: 12, paddingTop: 12, borderTop: "1px solid #F3F4F6" },
  resultHead:  { fontSize: 12, fontWeight: 600, color: "#374151", marginBottom: 6 },
  resultItem:  { fontSize: 12, color: "#374151", padding: "6px 0", borderBottom: "1px solid #F9FAFB" },
  rowNo:       { fontFamily: "monospace", color: "#9CA3AF", marginRight: 6 },
  errTh:       { textAlign: "left", padding: "6px 8px", borderBottom: "1px solid #E5E7EB", color: "#6B7280", fontWeight: 600, whiteSpace: "nowrap" },
  errTd:       { padding: "6px 8px", borderBottom: "1px solid #F3F4F6", color: "#374151", verticalAlign: "top" },
  tagLabel:    { fontSize: 10, color: "#9CA3AF", marginRight: 2 },
  synTag:      { fontSize: 11, padding: "2px 8px", borderRadius: 12, background: "#F5F3FF", color: "#5B21B6", border: "1px solid #DDD6FE" },
  btnCreate:   { padding: "8px 14px", fontSize: 12, fontWeight: 600, border: "1.5px solid #1565C0", borderRadius: 8, background: "#fff", color: "#1565C0", cursor: "pointer", fontFamily: "inherit" },

  historyItem: { padding: "12px 0", borderBottom: "1px solid #F3F4F6" },
  diffRow:     { display: "flex", alignItems: "center", gap: 8, fontSize: 12, marginBottom: 6, flexWrap: "wrap" },
  diffBefore:  { color: "#B91C1C", textDecoration: "line-through" },
  diffAfter:   { color: "#065F46", fontWeight: 500 },
  page:        { padding: 28 },
  pageMobile:  { padding: 16 },
  pageTitle:   { fontSize: 20, fontWeight: 700, color: "#111827", marginBottom: 4 },
  pageSub:     { fontSize: 13, color: "#6B7280", marginBottom: 20 },
  tabs:        { display: "flex", gap: 2, background: "#F3F4F6", borderRadius: 10, padding: 4, width: "fit-content", marginBottom: 20 },
  tab:         { padding: "8px 20px", borderRadius: 8, fontSize: 13, cursor: "pointer", color: "#6B7280" },
  tabActive:   { background: "#fff", color: "#1565C0", fontWeight: 600, boxShadow: "0 1px 3px rgba(0,0,0,.08)" },
  searchInput: { height: 38, padding: "0 14px", border: "1.5px solid #D1D5DB", borderRadius: 8, fontSize: 13, fontFamily: "inherit", outline: "none", width: 280, boxSizing: "border-box" },
  btnSearch:   { height: 38, padding: "0 18px", background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  filterChip:  { padding: "5px 14px", background: "#F3F4F6", color: "#6B7280", border: "none", borderRadius: 20, fontSize: 12, cursor: "pointer", fontFamily: "inherit" },
  filterChipActive: { background: "#1565C0", color: "#fff", fontWeight: 600 },
  faqCard:     { background: "#fff", borderRadius: 12, padding: 18, marginBottom: 10, boxShadow: "0 1px 3px rgba(0,0,0,.06)", border: "1px solid rgba(0,0,0,.06)" },
  candCard:    { background: "#fff", borderRadius: 12, padding: 18, marginBottom: 12, boxShadow: "0 1px 3px rgba(0,0,0,.06)", border: "1px solid rgba(0,0,0,.06)" },
  faqQ:        { fontSize: 14, fontWeight: 600, color: "#111827", marginBottom: 4 },
  faqA:        { fontSize: 12, color: "#6B7280", lineHeight: 1.65 },
  tag:         { fontSize: 11, padding: "2px 9px", background: "#EFF8FF", color: "#1565C0", borderRadius: 12, border: "1px solid #BFDBFE" },
  categoryTag: { fontSize: 10, fontWeight: 600, padding: "2px 8px", borderRadius: 10, background: "#F0FDF4", color: "#166534", border: "1px solid #BBF7D0" },
  meta:        { fontSize: 11, color: "#6B7280", background: "#F3F4F6", padding: "2px 8px", borderRadius: 10 },
  matchedBox:  { marginTop: 10, padding: "10px 12px", background: "#F9FAFB", borderRadius: 8, border: "1px solid #F3F4F6" },
  infoBanner:  { background: "#EFF8FF", border: "1px solid #BFDBFE", borderRadius: 10, padding: "12px 16px", fontSize: 13, color: "#1E40AF", marginBottom: 16 },
  empty:       { background: "#fff", borderRadius: 12, padding: 48, textAlign: "center", color: "#9CA3AF", fontSize: 14 },
  btnSm:       { padding: "6px 14px", background: "#F3F4F6", color: "#374151", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 500, cursor: "pointer", fontFamily: "inherit" },
  btnSmDanger: { padding: "6px 14px", background: "#FDECEA", color: "#B91C1C", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 500, cursor: "pointer", fontFamily: "inherit" },
  btnAccept:   { padding: "6px 14px", background: "#D1FAE5", color: "#065F46", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnReject:   { padding: "6px 14px", background: "#FDECEA", color: "#B91C1C", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  input:       { width: "100%", height: 42, padding: "0 14px", border: "1.5px solid #D1D5DB", borderRadius: 8, fontSize: 13, fontFamily: "inherit", outline: "none", boxSizing: "border-box" },
  modalBg:     { position: "fixed", inset: 0, background: "rgba(0,0,0,.45)", zIndex: 1000, display: "flex", alignItems: "center", justifyContent: "center", padding: 20 },
  modal:       { background: "#fff", borderRadius: 16, padding: 28, maxWidth: 540, width: "100%", maxHeight: "86vh", overflowY: "auto", boxShadow: "0 20px 60px rgba(0,0,0,.2)" },
  modalTitle:  { fontSize: 17, fontWeight: 700, color: "#111827", marginBottom: 16 },
  modalLabel:  { fontSize: 11, fontWeight: 600, color: "#6B7280", textTransform: "uppercase", letterSpacing: ".06em", marginBottom: 5, display: "block" },
  modalValue:  { fontSize: 13, color: "#111827" },
  modalActions:{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 20 },
  pager:       { display: "flex", alignItems: "center", justifyContent: "center", gap: 14, marginTop: 16 },
};
