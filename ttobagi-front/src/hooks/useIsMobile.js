import { useSyncExternalStore } from "react";

// 폰·좁은 창에서만 레이아웃을 바꾼다. 이 값이 false면 PC에서 쓰던 스타일이
// 그대로 쓰이므로 넓은 화면의 모양은 조금도 달라지지 않는다.
//
// 인라인 스타일에는 미디어쿼리를 쓸 수 없어(이 프로젝트는 스타일이 전부 인라인)
// matchMedia로 폭을 읽어 스타일 객체를 갈아끼운다.
export const MOBILE_MAX_WIDTH = 768;
const QUERY = `(max-width: ${MOBILE_MAX_WIDTH}px)`;

// matchMedia는 React 밖의 상태라 useSyncExternalStore로 구독한다.
// useEffect + setState로 하면 첫 렌더 뒤 한 번 더 그려야 해서 깜빡인다.
let mql = null;
const media = () => (mql ??= window.matchMedia(QUERY));

function subscribe(onChange) {
  const m = media();
  m.addEventListener("change", onChange);
  return () => m.removeEventListener("change", onChange);
}

export function useIsMobile() {
  return useSyncExternalStore(
    subscribe,
    () => media().matches,
    () => false, // 서버 렌더 시에는 넓은 화면으로 본다
  );
}
