// 분석 진행 단계.
//
// AI가 어느 대목을 지나고 있는지 GET /analyze/{id}.progressDetail 과 SSE progress 이벤트로 온다.
// 계약 v3 4절의 phase 열 가지를 그대로 쓴다.
//
// 세 가지를 지켜야 한다.
//
// 1) 생략된 단계가 있어도 실패가 아니다. 조건에 안 맞으면 건너뛴다(계약 4절).
//    앞 단계가 안 보였다고 문제로 보이게 만들지 않는다.
// 2) progressDetail 은 AI가 보내기 전까지 null 이다. 과거 작업에도 없다.
//    그때는 단계 없이 돌고 있다는 것만 알린다.
// 3) delayed 는 진행 정보를 못 받고 있다는 뜻이지 분석이 멈췄다는 뜻이 아니다.

// 계약 4절 순서 그대로. 화면에서도 이 차례로 보여 준다.
export const PHASES = [
  { key: "QUEUED",              label: "대기" },
  { key: "CLASSIFYING",         label: "유형 분류" },
  { key: "PREPROCESSING",       label: "전처리" },
  { key: "EMBEDDING",           label: "문장 임베딩" },
  { key: "DIMENSION_REDUCTION", label: "차원 축소" },
  { key: "CLUSTERING",          label: "군집화" },
  { key: "FAQ_GENERATION",      label: "FAQ 초안 생성" },
  { key: "RETRIEVING",          label: "기존 답변 검색" },
  { key: "ANSWER_PIPELINE",     label: "답변 생성·검증" },
  { key: "FINALIZING",          label: "결과 정리" },
];

const INDEX = new Map(PHASES.map((p, i) => [p.key, i]));

// 모르는 phase가 와도 화면을 비우지 않는다. 값 그대로 보여 준다.
export function phaseLabel(phase) {
  if (!phase) return null;
  const i = INDEX.get(phase);
  return i === undefined ? phase : PHASES[i].label;
}

// 지금 단계가 몇 번째인지. 모르는 값이면 -1.
export function phaseIndex(phase) {
  const i = INDEX.get(phase);
  return i === undefined ? -1 : i;
}

const UNIT_LABEL = { LOG: "건", CLUSTER: "개 묶음", STEP: "단계" };

// "32 / 1,200건" 처럼. 한쪽이라도 모르면 적지 않는다(계약 4절: 둘 다 null).
export function unitProgressText(detail) {
  const done = detail?.completedUnits;
  const total = detail?.totalUnits;
  if (typeof done !== "number" || typeof total !== "number") return null;
  const unit = UNIT_LABEL[detail?.unit] || "";
  return `${done.toLocaleString()} / ${total.toLocaleString()}${unit}`;
}

// 0~100. 막대를 그릴 수 있을 때만 숫자를 준다.
export function unitPercent(detail) {
  const done = detail?.completedUnits;
  const total = detail?.totalUnits;
  if (typeof done !== "number" || typeof total !== "number" || total <= 0) return null;
  return Math.min(100, Math.max(0, (done / total) * 100));
}

// AI가 보고한 경과 시간. 화면이 따로 세는 시간과 다를 수 있어 그대로 쓴다.
// 대기열에 있는 동안은 0이다(계약 4절).
export function elapsedText(detail) {
  const sec = detail?.elapsedSeconds;
  if (typeof sec !== "number" || sec < 0) return null;
  const total = Math.floor(sec);
  const m = Math.floor(total / 60);
  const r = total % 60;
  return m > 0 ? `${m}분 ${String(r).padStart(2, "0")}초` : `${r}초`;
}

// 진행 정보가 늦어지고 있을 때의 안내.
// 실패가 아니다. 분석은 계속 돌고 있을 수 있다(계약 4절: 지연만으로 실패 처리하지 않는다).
export function delayNotice(detail) {
  if (!detail?.delayed) return null;
  return "진행 정보 확인이 지연되고 있습니다. 분석은 계속 진행 중일 수 있습니다.";
}
