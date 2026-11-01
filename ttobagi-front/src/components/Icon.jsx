// 선(stroke) 기반 아이콘 모음.
//
// 이모지는 기기·OS마다 모양과 크기가 달라 글자와 높이가 안 맞고 색도 못 바꾼다.
// 여기 아이콘은 currentColor를 따르므로 주변 글자색과 저절로 맞고, 굵기도 한곳에서 정한다.
//
// 24x24 좌표계에 1.75 굵기, 끝과 모서리는 둥글게 — 한 벌로 보이게 맞춘 값이다.

const PATHS = {
  // 왼쪽 메뉴
  dashboard: (
    <>
      <path d="M4 20h16" />
      <path d="M7 20v-6" />
      <path d="M12 20V7" />
      <path d="M17 20v-9" />
    </>
  ),
  analyze: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="m20 20-4.2-4.2" />
    </>
  ),
  faq: (
    <>
      <path d="M4 6.5h16" />
      <path d="M4 12h16" />
      <path d="M4 17.5h9" />
    </>
  ),
  versions: (
    <>
      <path d="M12 3 3.5 7.5 12 12l8.5-4.5L12 3Z" />
      <path d="m3.5 12.5 8.5 4.5 8.5-4.5" />
    </>
  ),
  audit: (
    <>
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z" />
      <path d="M14 3v5h5" />
      <path d="M9 13h6" />
      <path d="M9 17h4" />
    </>
  ),
  account: (
    <>
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5 20a7 7 0 0 1 14 0" />
    </>
  ),

  // 상단 바
  menu: (
    <>
      <path d="M4 7h16" />
      <path d="M4 12h16" />
      <path d="M4 17h16" />
    </>
  ),

  // 로고 — 전동차를 단순하게
  train: (
    <>
      <rect x="5" y="3.5" width="14" height="13" rx="4" />
      <path d="M5 11h14" />
      <circle cx="9" cy="13.8" r=".9" fill="currentColor" stroke="none" />
      <circle cx="15" cy="13.8" r=".9" fill="currentColor" stroke="none" />
      <path d="m9 17-2 3.5" />
      <path d="m15 17 2 3.5" />
    </>
  ),

  // 입력·상태
  mail: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="3" />
      <path d="m4 8 7.1 4.6a2 2 0 0 0 2.2 0L20 8" />
    </>
  ),
  lock: (
    <>
      <rect x="4.5" y="10" width="15" height="10" rx="3" />
      <path d="M8.5 10V7a3.5 3.5 0 0 1 7 0v3" />
    </>
  ),
  upload: (
    <>
      <path d="M12 16V4" />
      <path d="m7.5 8.5 4.5-4.5 4.5 4.5" />
      <path d="M4 15v3a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-3" />
    </>
  ),
  check: <path d="m5 12.5 4.5 4.5L19 7" />,
  close: (
    <>
      <path d="m6 6 12 12" />
      <path d="M18 6 6 18" />
    </>
  ),

  // 비어 있는 화면
  empty: (
    <>
      <path d="M4 13h4l1.5 3h5L16 13h4" />
      <path d="M5.5 5.5h13l2.5 7.5v4a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-4l2.5-7.5Z" />
    </>
  ),
};

export default function Icon({ name, size = 18, strokeWidth = 1.75, style }) {
  const path = PATHS[name];
  if (!path) return null;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      style={{ flexShrink: 0, display: "block", ...style }}
    >
      {path}
    </svg>
  );
}
