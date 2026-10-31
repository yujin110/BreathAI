import { useCallback, useEffect, useState } from "react";
import { faqApi } from "../api/faq.js";
import { saveBlob } from "../api/client.js";
import { formatKst } from "../utils/datetime.js";
import { useToast } from "../hooks/useToast.js";
import { toPlainText } from "../utils/text.js";
import { useAuth } from "../hooks/useAuth.js";
import { useIsMobile } from "../hooks/useIsMobile.js";

const PAGE_SIZE = 10;

// 백엔드 FaqEditHistory.EditType — 버전 상세의 changes[]에 실려온다
const CHANGE_LABEL = {
  CREATE: { text: "신규", background: "#DBEAFE", color: "#1E40AF" },
  EXPAND: { text: "확장", background: "#EDE9FE", color: "#5B21B6" },
  MANUAL: { text: "수정", background: "#FEF3C7", color: "#92400E" },
  DELETE: { text: "삭제", background: "#FDECEA", color: "#B91C1C" },
};

// 백엔드 FaqVersion.VersionType
const VERSION_TYPE_LABEL = {
  UPLOAD: "파일 업로드", DOWNLOAD: "다운로드 시점",
  MANUAL: "직접 만듦", RESTORE: "복원",
};

// 버전 비교 응답의 changes[].field
const FIELD_LABEL = {
  standardQuestion: "질문", answer: "답변", keywords: "키워드",
  synonyms: "유사어", qType: "카테고리 코드", category: "카테고리",
};

// keywords·synonyms 는 배열로 오고 나머지는 문자열이다.
// answer 에는 HTML·이미지가 들어 있을 수 있어 FAQ 목록·이력과 같게 태그를 걷어낸다.
function fieldText(field, value) {
  if (value == null) return "(없음)";
  if (Array.isArray(value)) return value.join(", ");
  const text = String(value);
  return field === "answer" ? toPlainText(text) : text;
}

export default function FaqVersionsPage() {
  const isMobile = useIsMobile();
  const [versions, setVersions] = useState([]);
  const [page, setPage] = useState(0);
  const [pageInfo, setPageInfo] = useState({ totalCount: 0, totalPages: 0 });
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState(null); // { loading, data }
  const [downloadingId, setDownloadingId] = useState(null);
  const [creating, setCreating] = useState(false);
  const [restoringId, setRestoringId] = useState(null);
  const [compare, setCompare] = useState(null);   // { loading, data }
  const [baseId, setBaseId] = useState("");       // 비교 기준 버전
  const [targetId, setTargetId] = useState("");   // 빈 값이면 현재 운영 FAQ와 비교
  const { user } = useAuth();
  const toast = useToast();

  // 버전 만들기·복원은 백엔드가 @PreAuthorize("hasRole('ADMIN')")로 막는다
  const isAdmin = user?.role === "ADMIN";

  const applyList = useCallback((res) => {
    setVersions(res.data?.versions || []);
    setPageInfo({
      totalCount: res.data?.totalCount ?? 0,
      totalPages: res.data?.totalPages ?? 0,
    });
  }, []);

  useEffect(() => {
    faqApi.getVersions(0, PAGE_SIZE)
      .then((res) => {
        applyList(res);
        setLoading(false);
      })
      .catch((err) => {
        toast.error(err.message || "버전 목록을 불러오지 못했습니다.");
        setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applyList]);

  const goToPage = async (next) => {
    try {
      const res = await faqApi.getVersions(next, PAGE_SIZE);
      applyList(res);
      setPage(next);
    } catch (err) {
      toast.error(err.message || "버전 목록을 불러오지 못했습니다.");
    }
  };

  const openDetail = async (versionId) => {
    setDetail({ loading: true, data: null });
    try {
      const res = await faqApi.getVersionDetail(versionId);
      setDetail({ loading: false, data: res.data });
    } catch (err) {
      toast.error(err.message || "버전 상세를 불러오지 못했습니다.");
      setDetail(null);
    }
  };

  // versionId를 생략하면 서버가 최신 내용으로 새 버전(VersionType.DOWNLOAD)을 만들고 내려준다.
  // 그래서 "최신 내용 다운로드" 후에는 목록을 다시 받아야 새 버전이 보인다.
  const handleDownload = async (versionId) => {
    setDownloadingId(versionId ?? "latest");
    try {
      const { blob, fileName } = await faqApi.downloadCsv(versionId);
      saveBlob(blob, fileName);
      toast.success(`${fileName} 다운로드를 시작했습니다.`);
      if (versionId == null) {
        const res = await faqApi.getVersions(0, PAGE_SIZE);
        applyList(res);
        setPage(0);
      }
    } catch (err) {
      toast.error(err.message || "CSV 다운로드에 실패했습니다.");
    } finally {
      setDownloadingId(null);
    }
  };

  // 다운로드하지 않고 지금 상태를 버전으로 저장한다
  const handleCreateVersion = async () => {
    setCreating(true);
    try {
      const res = await faqApi.createVersion();
      toast.success(`${res.data?.versionName || "새 버전"}을 만들었습니다.`);
      const list = await faqApi.getVersions(0, PAGE_SIZE);
      applyList(list);
      setPage(0);
    } catch (err) {
      toast.error(err.message || "버전을 만들지 못했습니다.");
    } finally {
      setCreating(false);
    }
  };

  // 그 버전 이후의 수정·등록·삭제와 후보 반영이 모두 취소되고 결과가 새 버전으로 저장된다
  const handleRestore = async (version) => {
    const name = version.versionName || `버전 #${version.versionId}`;
    if (!window.confirm(
      `현재 FAQ를 ${name} 내용으로 되돌립니다.\n` +
      "이 버전 이후의 수정·등록·삭제와 후보 반영이 모두 취소되고, 결과는 새 버전으로 저장됩니다."
    )) return;

    setRestoringId(version.versionId);
    try {
      const d = (await faqApi.restoreVersion(version.versionId)).data || {};
      // 바뀐 게 없으면 새 버전을 만들지 않는다
      if (!d.newVersionId) {
        toast.info("바뀐 내용이 없습니다.");
      } else {
        toast.success(
          `수정 ${d.updatedCount ?? 0}건, 삭제 ${d.deletedCount ?? 0}건, ` +
          `되살림 ${d.reactivatedCount ?? 0}건, 후보 ${d.revertedCandidateCount ?? 0}건이 대기로 돌아갔습니다. ` +
          `새 버전 ${d.newVersionName || `#${d.newVersionId}`}이 만들어졌습니다.`
        );
      }
      setDetail(null);
      const list = await faqApi.getVersions(0, PAGE_SIZE);
      applyList(list);
      setPage(0);
    } catch (err) {
      toast.error(err.message || "복원에 실패했습니다.");
    } finally {
      setRestoringId(null);
    }
  };

  // targetId 를 비우면 기준 버전과 현재 운영 FAQ 를 비교한다
  const runCompare = async () => {
    if (!baseId) { toast.error("기준 버전을 선택해주세요."); return; }
    if (baseId === targetId) { toast.error("서로 다른 버전을 선택해주세요."); return; }
    setCompare({ loading: true, data: null });
    try {
      const res = await faqApi.compareVersions(baseId, targetId || undefined);
      setCompare({ loading: false, data: res.data });
    } catch (err) {
      toast.error(err.message || "버전 비교에 실패했습니다.");
      setCompare(null);
    }
  };

  if (loading) return <div style={{ padding: 40, color: "#6B7280" }}>불러오는 중...</div>;

  return (
    <div style={{ ...S.page, ...(isMobile ? S.pageMobile : {}) }}>
      <div style={S.pageTitle}>FAQ 버전 관리</div>
      <div style={S.pageSub}>
        버전 만들기, CSV 다운로드, FAQ 파일 일괄 업로드로 그 시점의 FAQ 스냅샷이 쌓입니다. 버전별로 CSV를 내려받을 수 있습니다.
      </div>

      <div style={{ marginBottom: 14, display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
        <button style={S.btnPrimary} disabled={downloadingId === "latest"}
          onClick={() => handleDownload(undefined)}>
          {downloadingId === "latest" ? "내려받는 중..." : "최신 내용 CSV 다운로드"}
        </button>
        {isAdmin && (
          <button style={S.btnOutline} disabled={creating} onClick={handleCreateVersion}>
            {creating ? "만드는 중..." : "버전 만들기"}
          </button>
        )}
        <span style={{ fontSize: 12, color: "#6B7280" }}>
          총 {pageInfo.totalCount.toLocaleString()}개 버전
          {pageInfo.totalPages > 1 && ` · ${page + 1}/${pageInfo.totalPages} 페이지`}
        </span>
      </div>
      <div style={{ fontSize: 11, color: "#9CA3AF", marginBottom: 16 }}>
        최신 내용을 내려받으면 그 시점의 스냅샷이 새 버전으로 기록됩니다.
        {isAdmin && " 버전 만들기는 내려받지 않고 지금 상태만 저장합니다."}
      </div>

      {/* 버전 비교 — 비교 대상을 비우면 현재 운영 FAQ와 비교한다 */}
      <div style={S.compareBar}>
        <span style={{ fontSize: 12, fontWeight: 600, color: "#374151" }}>버전 비교</span>
        <select style={S.compareSelect} value={baseId} onChange={(e) => setBaseId(e.target.value)}>
          <option value="">기준 버전</option>
          {versions.map((v) => (
            <option key={v.versionId} value={v.versionId}>{v.versionName || `#${v.versionId}`}</option>
          ))}
        </select>
        <span style={{ color: "#9CA3AF" }}>→</span>
        <select style={S.compareSelect} value={targetId} onChange={(e) => setTargetId(e.target.value)}>
          <option value="">현재 운영 FAQ</option>
          {versions.map((v) => (
            <option key={v.versionId} value={v.versionId}>{v.versionName || `#${v.versionId}`}</option>
          ))}
        </select>
        <button style={S.btnSm} onClick={runCompare}>비교</button>
      </div>

      {versions.length === 0 ? (
        <div style={S.empty}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>🗂️</div>
          <div>아직 버전이 없습니다.</div>
          <div style={{ fontSize: 12, marginTop: 6 }}>CSV를 내려받거나 버전 만들기를 누르면 생성됩니다. FAQ 파일을 일괄 업로드해도 생깁니다.</div>
        </div>
      ) : (
        versions.map((v) => (
          <div key={v.versionId} style={S.card}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 12 }}>
              <div style={{ flex: 1 }}>
                <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 6, flexWrap: "wrap" }}>
                  <span style={S.versionName}>{v.versionName || `버전 #${v.versionId}`}</span>
                  {v.isLatest && <span style={S.latestTag}>최신</span>}
                  <span style={S.typeTag}>{VERSION_TYPE_LABEL[v.versionType] || v.versionType}</span>
                </div>
                <div style={{ display: "flex", gap: 14, fontSize: 12, color: "#6B7280", flexWrap: "wrap" }}>
                  <span>전체 <strong style={{ color: "#111827" }}>{(v.totalFaqCount ?? 0).toLocaleString()}</strong>건</span>
                  <span>신규 <strong style={{ color: "#1565C0" }}>{v.createdCount ?? 0}</strong></span>
                  <span>확장 <strong style={{ color: "#5B21B6" }}>{v.expandedCount ?? 0}</strong></span>
                </div>
                <div style={{ fontSize: 11, color: "#9CA3AF", marginTop: 6 }}>
                  {formatKst(v.createdAt)} · {v.createdBy || "-"}
                </div>
              </div>
              <div style={{ display: "flex", gap: 6 }}>
                <button style={S.btnSm} onClick={() => openDetail(v.versionId)}>상세</button>
                <button style={S.btnSm} disabled={downloadingId === v.versionId}
                  onClick={() => handleDownload(v.versionId)}>
                  {downloadingId === v.versionId ? "..." : "CSV"}
                </button>
              </div>
            </div>
          </div>
        ))
      )}

      {pageInfo.totalPages > 1 && (
        <div style={S.pager}>
          <button style={S.btnSm} disabled={page === 0} onClick={() => goToPage(page - 1)}>← 이전</button>
          <span style={{ fontSize: 12, color: "#6B7280" }}>{page + 1} / {pageInfo.totalPages}</span>
          <button style={S.btnSm} disabled={page >= pageInfo.totalPages - 1} onClick={() => goToPage(page + 1)}>다음 →</button>
        </div>
      )}

      {/* ── 버전 상세 모달 ── */}
      {detail && (
        <div style={S.modalBg} onClick={() => setDetail(null)}>
          <div style={S.modal} onClick={(e) => e.stopPropagation()}>
            {detail.loading ? (
              <div style={{ padding: 20, textAlign: "center", color: "#9CA3AF", fontSize: 13 }}>불러오는 중...</div>
            ) : (
              <>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 10, marginBottom: 10 }}>
                  <div style={{ ...S.modalTitle, marginBottom: 0 }}>
                    {detail.data?.versionName || `버전 #${detail.data?.versionId}`}
                  </div>
                  {isAdmin && (
                    <button style={S.btnOutline} disabled={restoringId === detail.data?.versionId}
                      onClick={() => handleRestore(detail.data)}>
                      {restoringId === detail.data?.versionId ? "복원 중..." : "이 버전으로 복원"}
                    </button>
                  )}
                </div>

                <div style={{ ...S.statRow, ...(isMobile ? { gridTemplateColumns: "repeat(3, 1fr)" } : {}) }}>
                  {[
                    { label: "전체", value: detail.data?.totalFaqCount ?? 0, color: "#111827" },
                    { label: "신규", value: detail.data?.createdCount ?? 0, color: "#1565C0" },
                    { label: "확장", value: detail.data?.expandedCount ?? 0, color: "#5B21B6" },
                    { label: "수정", value: detail.data?.manualCount ?? 0, color: "#92400E" },
                    { label: "삭제", value: detail.data?.deletedCount ?? 0, color: "#B91C1C" },
                  ].map((s) => (
                    <div key={s.label} style={S.statBox}>
                      <div style={{ fontSize: 11, color: "#6B7280", marginBottom: 4 }}>{s.label}</div>
                      <div style={{ fontSize: 18, fontWeight: 700, color: s.color }}>{s.value.toLocaleString()}</div>
                    </div>
                  ))}
                </div>

                <div style={{ fontSize: 11, color: "#9CA3AF", marginBottom: 14 }}>
                  {formatKst(detail.data?.createdAt)} · {detail.data?.createdBy || "-"}
                  {detail.data?.previousVersionId && ` · 이전 버전 #${detail.data.previousVersionId}`}
                </div>

                <div style={S.modalLabel}>이 버전의 변경 내역 ({detail.data?.changes?.length ?? 0})</div>
                {!detail.data?.changes?.length ? (
                  <div style={{ fontSize: 13, color: "#9CA3AF", padding: "16px 0" }}>변경 내역이 없습니다.</div>
                ) : (
                  <div style={{ display: "flex", flexDirection: "column", gap: 8, maxHeight: 360, overflowY: "auto" }}>
                    {detail.data.changes.map((c) => {
                      const tag = CHANGE_LABEL[c.editType] || CHANGE_LABEL.MANUAL;
                      return (
                        <div key={c.historyId} style={S.changeItem}>
                          <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 4 }}>
                            <span style={{
                              fontSize: 10, fontWeight: 600, padding: "2px 8px", borderRadius: 10,
                              background: tag.background, color: tag.color,
                            }}>{tag.text}</span>
                            <span style={{ fontSize: 11, fontFamily: "monospace", color: "#9CA3AF" }}>FAQ #{c.faqId}</span>
                          </div>
                          <div style={{ fontSize: 13, color: "#111827" }}>{c.standardQuestion}</div>
                        </div>
                      );
                    })}
                  </div>
                )}

                <div style={S.modalActions}>
                  <button style={S.btnSm} onClick={() => setDetail(null)}>닫기</button>
                  <button style={S.btnPrimarySm} disabled={downloadingId === detail.data?.versionId}
                    onClick={() => handleDownload(detail.data?.versionId)}>
                    CSV 다운로드
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* ── 버전 비교 결과 ── */}
      {compare && (
        <div style={S.modalBg} onClick={() => setCompare(null)}>
          <div style={{ ...S.modal, maxWidth: 760 }} onClick={(e) => e.stopPropagation()}>
            {compare.loading ? (
              <div style={{ padding: 20, textAlign: "center", color: "#9CA3AF", fontSize: 13 }}>비교 중...</div>
            ) : (
              <>
                <div style={S.modalTitle}>버전 비교</div>
                <div style={{ fontSize: 12, color: "#6B7280", marginBottom: 14 }}>
                  {compare.data?.baseVersionName || `#${compare.data?.baseVersionId}`}
                  {" → "}
                  {/* targetId 를 비우면 응답에 targetVersionId 가 없다 */}
                  {compare.data?.targetVersionId
                    ? (compare.data?.targetVersionName || `#${compare.data.targetVersionId}`)
                    : "현재 운영 FAQ"}
                </div>

                <div style={{ ...S.statRow, ...(isMobile ? { gridTemplateColumns: "repeat(3, 1fr)" } : {}) }}>
                  {[
                    { label: "추가", value: compare.data?.addedCount ?? 0, color: "#1565C0" },
                    { label: "삭제", value: compare.data?.deletedCount ?? 0, color: "#B91C1C" },
                    { label: "변경", value: compare.data?.changedCount ?? 0, color: "#92400E" },
                  ].map((s2) => (
                    <div key={s2.label} style={S.statBox}>
                      <div style={{ fontSize: 11, color: "#6B7280", marginBottom: 4 }}>{s2.label}</div>
                      <div style={{ fontSize: 18, fontWeight: 700, color: s2.color }}>{s2.value.toLocaleString()}</div>
                    </div>
                  ))}
                </div>

                <div style={{ maxHeight: 420, overflowY: "auto" }}>
                  {[
                    { key: "added", title: "추가된 FAQ", color: "#1565C0" },
                    { key: "deleted", title: "삭제된 FAQ", color: "#B91C1C" },
                  ].map((sec) => (
                    (compare.data?.[sec.key] || []).length > 0 && (
                      <div key={sec.key} style={S.diffSection}>
                        <div style={{ ...S.diffHead, color: sec.color }}>{sec.title}</div>
                        {compare.data[sec.key].map((f) => (
                          <div key={f.faqId} style={S.diffItem}>
                            <span style={{ color: "#9CA3AF", marginRight: 6 }}>#{f.faqId}</span>
                            {f.category && <span style={S.typeTag}>{f.category}</span>}{" "}
                            {f.standardQuestion}
                          </div>
                        ))}
                      </div>
                    )
                  ))}

                  {(compare.data?.changed || []).length > 0 && (
                    <div style={S.diffSection}>
                      <div style={{ ...S.diffHead, color: "#92400E" }}>변경된 FAQ</div>
                      {compare.data.changed.map((f) => (
                        <div key={f.faqId} style={S.diffItem}>
                          <div style={{ marginBottom: 6 }}>
                            <span style={{ color: "#9CA3AF", marginRight: 6 }}>#{f.faqId}</span>
                            <strong>{f.standardQuestion}</strong>
                          </div>
                          {/* 바뀐 칸만 온다. keywords 는 배열이라 fieldText 로 펼친다. */}
                          {(f.changes || []).map((c, i) => (
                            <div key={i} style={{ marginBottom: 4, paddingLeft: 10 }}>
                              <span style={{ fontSize: 11, color: "#6B7280" }}>
                                {FIELD_LABEL[c.field] || c.field}
                              </span>
                              <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "baseline" }}>
                                <span style={S.diffBefore}>{fieldText(c.field, c.before)}</span>
                                <span style={{ color: "#9CA3AF" }}>→</span>
                                <span style={S.diffAfter}>{fieldText(c.field, c.after)}</span>
                              </div>
                            </div>
                          ))}
                        </div>
                      ))}
                    </div>
                  )}

                  {!compare.data?.addedCount && !compare.data?.deletedCount && !compare.data?.changedCount && (
                    <div style={{ padding: 20, textAlign: "center", color: "#9CA3AF", fontSize: 13 }}>
                      두 버전의 내용이 같습니다.
                    </div>
                  )}
                </div>

                <div style={S.modalActions}>
                  <button style={S.btnSm} onClick={() => setCompare(null)}>닫기</button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

const S = {
  btnOutline:   { padding: "9px 16px", fontSize: 13, fontWeight: 600, border: "1.5px solid #1565C0", borderRadius: 8, background: "#fff", color: "#1565C0", cursor: "pointer", fontFamily: "inherit" },
  compareBar:   { display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", padding: "10px 14px", marginBottom: 16, background: "#F9FAFB", border: "1px solid #F3F4F6", borderRadius: 10 },
  compareSelect:{ padding: "6px 10px", fontSize: 12, border: "1px solid #D1D5DB", borderRadius: 7, background: "#fff", color: "#374151", cursor: "pointer", maxWidth: 220 },
  diffSection:  { marginTop: 14 },
  diffHead:     { fontSize: 12, fontWeight: 600, color: "#374151", marginBottom: 6 },
  diffItem:     { padding: "8px 0", borderBottom: "1px solid #F3F4F6", fontSize: 12, color: "#374151" },
  diffBefore:   { color: "#B91C1C", textDecoration: "line-through" },
  diffAfter:    { color: "#065F46", fontWeight: 500 },

  page:        { padding: 28 },
  pageMobile:  { padding: 16 },
  pageTitle:   { fontSize: 20, fontWeight: 700, color: "#111827", marginBottom: 4 },
  pageSub:     { fontSize: 13, color: "#6B7280", marginBottom: 20, lineHeight: 1.6 },
  card:        { background: "#fff", borderRadius: 12, padding: 18, marginBottom: 10, boxShadow: "0 1px 3px rgba(0,0,0,.06)", border: "1px solid rgba(0,0,0,.06)" },
  versionName: { fontSize: 14, fontWeight: 600, color: "#111827" },
  latestTag:   { fontSize: 10, fontWeight: 600, padding: "2px 8px", borderRadius: 10, background: "#D1FAE5", color: "#065F46", border: "1px solid #A7F3D0" },
  typeTag:     { fontSize: 10, fontWeight: 600, padding: "2px 8px", borderRadius: 10, background: "#F3F4F6", color: "#6B7280" },
  empty:       { background: "#fff", borderRadius: 12, padding: 48, textAlign: "center", color: "#9CA3AF", fontSize: 14 },
  btnPrimary:  { height: 38, padding: "0 18px", background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnPrimarySm:{ padding: "6px 16px", background: "#1565C0", color: "#fff", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 600, cursor: "pointer", fontFamily: "inherit" },
  btnSm:       { padding: "6px 14px", background: "#F3F4F6", color: "#374151", border: "none", borderRadius: 8, fontSize: 12, fontWeight: 500, cursor: "pointer", fontFamily: "inherit" },
  pager:       { display: "flex", alignItems: "center", justifyContent: "center", gap: 14, marginTop: 16 },
  modalBg:     { position: "fixed", inset: 0, background: "rgba(0,0,0,.45)", zIndex: 1000, display: "flex", alignItems: "center", justifyContent: "center", padding: 20 },
  modal:       { background: "#fff", borderRadius: 16, padding: 28, maxWidth: 560, width: "100%", maxHeight: "86vh", overflowY: "auto", boxShadow: "0 20px 60px rgba(0,0,0,.2)" },
  modalTitle:  { fontSize: 17, fontWeight: 700, color: "#111827", marginBottom: 16 },
  modalLabel:  { fontSize: 11, fontWeight: 600, color: "#6B7280", textTransform: "uppercase", letterSpacing: ".06em", marginBottom: 8, display: "block" },
  modalActions:{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 20 },
  statRow:     { display: "grid", gridTemplateColumns: "repeat(5, 1fr)", gap: 8, marginBottom: 12 },
  statBox:     { textAlign: "center", padding: "10px 6px", background: "#F9FAFB", borderRadius: 8 },
  changeItem:  { padding: "10px 12px", background: "#F9FAFB", borderRadius: 8, border: "1px solid #F3F4F6" },
};
