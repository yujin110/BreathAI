// FAQ 후보가 기존 챗봇 학습데이터의 어떤 질문-답변 쌍과 겹치는지 보여주는 뱃지.
// 백엔드 FaqCandidateListResponse.FaqCandidateItem.matchedFaqs를 그대로 쓴다.
// matchedFaqId와 question은 해당 항목이 운영 FAQ(gold_faq)에 없으면 null이고,
// matchedFaqSeqNum은 bronze_counselling_info.seq_num이다.
//
// 후보 타입이 EXPAND면 백엔드 applyFaqCandidate가 신규 생성이 아니라
// 기존 FAQ를 확장하므로, 검토자가 그 사실을 미리 알 수 있게 구분해 표시한다.
export default function MatchedFaqBadge({ matches, candidateType, provenance }) {
  const list = matches || [];
  const needsReview = provenance?.matchStatus === "REVIEW_REQUIRED";
  if (candidateType !== "EXPAND" && list.length === 0 && !needsReview) return null;

  const topScore = list.reduce((max, m) => Math.max(max, m.matchScore ?? 0), 0);
  const isExpand = candidateType === "EXPAND";
  const title = provenance?.matchDecisionReason || (list.length
    ? list
        .map((m) => `${m.question || `FAQ ${m.matchedFaqId ?? m.matchedFaqSeqNum}`} (점수 ${(m.matchScore ?? 0).toFixed(2)})`)
        .join("\n")
    : "기존 FAQ 키워드 확장 후보");

  return (
    <span
      title={title}
      style={{
        fontSize: 10,
        fontWeight: 600,
        padding: "2px 8px",
        borderRadius: 10,
        background: isExpand ? "#EDE9FE" : "#FEF3C7",
        color: isExpand ? "#5B21B6" : "#92400E",
        border: `1px solid ${isExpand ? "#DDD6FE" : "#FDE68A"}`,
        cursor: "help",
      }}
    >
      {needsReview ? "FAQ 매칭 검토 필요" : isExpand ? "기존 FAQ 확장" : "기존 FAQ 유사"}
      {list.length > 0 && ` ${list.length}건`}
      {topScore > 0 && ` · 점수 ${topScore.toFixed(2)}`}
    </span>
  );
}
