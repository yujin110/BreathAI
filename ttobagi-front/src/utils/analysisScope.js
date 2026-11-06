// 분석 범위 안내 문구.
//
// AI가 모든 질문을 군집화하지 않는다. 기본값 IMPROVEMENT는 미답변과 오답변만 고른다.
// 그래서 통계 카드(전체 기준)와 산점도·급증 키워드(선별 기준)의 숫자가 서로 다르다.
// 왜 다른지 화면에 적어 두지 않으면 "5,000건이라면서 점은 왜 1,500개냐"가 된다.
//
// 문구는 AI·BE·FE 공유 계약 v3 6절을 따른다.
// 화면에는 "오답변"으로 적고, 내부 값 LOW_QUALITY·lowQuality 는 그대로 둔다.

export const ANALYSIS_MODE = {
  IMPROVEMENT:    "IMPROVEMENT",
  UNANSWER_ONLY:  "UNANSWER_ONLY",
  ALL:            "ALL",
};

// 모드를 모르면 ALL로 본다. 신규 필드가 없는 과거 결과가 그렇다(계약 3절).
function modeOf(result) {
  const mode = result?.analysisMode;
  return ANALYSIS_MODE[mode] || ANALYSIS_MODE.ALL;
}

const n = (v) => (typeof v === "number" ? v : 0);

// 분석 대상이 무엇이었는지 한 줄로. 건수는 전체 samplingStatus에서 읽는다.
// 좌표 수를 대상 건수로 쓰지 않는다 — 좌표는 길이 필터를 지난 뒤의 수라 더 적다.
export function scopeSentence(result) {
  const total = n(result?.systemStatus?.totalLogCount);
  if (!total) return null;

  const s = result?.systemStatus?.samplingStatus;
  const unanswer   = n(s?.unanswer);
  const lowQuality = n(s?.lowQuality);
  const t = (v) => v.toLocaleString();

  switch (modeOf(result)) {
    case ANALYSIS_MODE.IMPROVEMENT:
      return `전체 ${t(total)}건 중 미답변 ${t(unanswer)}건·오답변 ${t(lowQuality)}건, 총 ${t(unanswer + lowQuality)}건 분석`;
    case ANALYSIS_MODE.UNANSWER_ONLY:
      return `전체 ${t(total)}건 중 미답변 ${t(unanswer)}건 분석`;
    default:
      return `전체 ${t(total)}건 분석`;
  }
}

// 군집도·급증 키워드 같은 카드에 붙이는 짧은 기준 표시.
export function scopeCaption(result) {
  switch (modeOf(result)) {
    case ANALYSIS_MODE.IMPROVEMENT:   return "미답변·오답변 질문 기준";
    case ANALYSIS_MODE.UNANSWER_ONLY: return "미답변 질문 기준";
    default:                          return "전체 질문 기준";
  }
}

// 대상이 0건일 때의 안내. 정상적인 결과이지 오류가 아니다.
export function emptyTargetSentence(result) {
  switch (modeOf(result)) {
    case ANALYSIS_MODE.IMPROVEMENT:   return "분석할 미답변·오답변 질문이 없습니다.";
    case ANALYSIS_MODE.UNANSWER_ONLY: return "분석할 미답변 질문이 없습니다.";
    default:                          return "분석할 질문이 없습니다.";
  }
}

// 유형도 매칭률도 없어 미답변으로 친 질문 수.
// 이 값이 크면 통계가 실제보다 미답변 쪽으로 기울어 있다는 뜻이라 밝혀 둔다.
// 과거 결과는 analysisScope 자체가 없어 null이고, 그때는 아무것도 적지 않는다.
export function legacyDefaultNotice(result) {
  const count = result?.analysisScope?.classificationSourceCounts?.LEGACY_DEFAULT;
  if (!count) return null;
  return `이 중 ${count.toLocaleString()}건은 응답 유형과 매칭률이 모두 없어 미답변으로 셌습니다.`;
}


// 분석이 왜 빈 결과로 끝났는지. 계약 v3 3절의 completionReason 다섯 가지다.
//
// 이 다섯은 모두 정상 완료다. 실패가 아니다.
// 빈 화면에 "분석을 먼저 실행해주세요"를 띄우면 분석을 안 한 것처럼 보이고,
// 오류로 적으면 다시 올리게 만든다. 둘 다 사실과 다르다.
//
// 무슨 일이 있었는지만 적는다. 해결책은 짐작으로 쓰지 않는다 —
// 기간을 넓히라거나 군집 크기를 낮추라는 말이 실제로 통하는지는 확인한 바 없다.
export function completionNotice(result) {
  const reason = result?.completionReason;
  if (!reason || reason === "RESULTS_GENERATED") return null;

  switch (reason) {
    case "NO_TARGET_LOGS":
      return {
        title: emptyTargetSentence(result),
        body: "올린 질문에 분석할 유형이 없어 군집화를 하지 않았습니다. 분석은 정상적으로 끝났고, 위 통계는 올린 전체 기준으로 그대로 볼 수 있습니다.",
      };
    case "NO_ELIGIBLE_TEXT":
      return {
        title: "분석할 수 있는 문장이 없습니다.",
        body: "대상 질문은 있었지만 모두 두 글자 미만이라 분석에서 빠졌습니다.",
      };
    case "INSUFFICIENT_TARGET_LOGS":
      return {
        title: "질문이 적어 군집화를 하지 않았습니다.",
        body: "분석할 질문은 있었지만 수가 적어 묶는 단계를 건너뛰었습니다.",
      };
    case "NO_FAQ_CLUSTERS":
      return {
        title: "FAQ로 만들 만한 묶음이 없습니다.",
        body: "질문을 묶어 보았지만 FAQ 생성 단계로 넘길 묶음이 없었습니다.",
      };
    default:
      // 모르는 값이 와도 빈 화면을 그냥 두지는 않는다
      return { title: "표시할 결과가 없습니다.", body: `분석은 끝났습니다. (${reason})` };
  }
}

// 끝난 분석인데 이 자리에 보여 줄 것이 없는 경우.
//
// "분석을 먼저 실행해주세요"는 안 돌린 사람에게 할 말이다. 끝난 분석에 그렇게 적으면
// 멀쩡한 파일을 다시 올리게 된다. 그렇다고 왜 비었는지 지어내지는 않는다.
// 완료 상태는 콜백이 결과를 모두 저장한 뒤에야 찍히므로(DashboardService.finish),
// 보통은 다른 탭에 결과가 있다. 그 사실만 적고 추측은 보태지 않는다.
export function emptyFinishedNotice(result) {
  if (!result) return null;
  if (completionNotice(result)) return null;   // 이유를 아는 경우는 그쪽이 맡는다
  return {
    title: "이 탭에 표시할 결과가 없습니다.",
    body: "분석은 끝났습니다. 다른 탭에서 결과를 확인해 보세요.",
  };
}

// 업로드 화면의 모드 선택지. 값은 계약 2절 그대로 대문자로 보낸다.
export const MODE_OPTIONS = [
  { value: "IMPROVEMENT",   label: "미답변·오답변 (기본)", hint: "답을 못 했거나 잘못 답한 질문만 묶습니다. FAQ 후보는 여기서 나옵니다." },
  { value: "UNANSWER_ONLY", label: "미답변만",            hint: "답을 못 한 질문만 봅니다." },
  { value: "ALL",           label: "전체",                hint: "잘 답한 질문까지 모두 묶습니다. 오래 걸리고 FAQ와 무관한 묶음이 섞입니다." },
];
