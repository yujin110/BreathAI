import { useEffect, useRef } from "react";
import Icon from "./Icon.jsx";

// 고른 값의 글자 길이에 맞춰 폭이 줄어드는 드롭다운.
//
// 두 가지를 해결한다.
//
// 1) <select>는 폭을 정할 때 "고른 값"이 아니라 "선택지 중 가장 긴 것"을 본다.
//    그래서 짧은 값을 골라도 상자가 넓은 채로 남는다.
//    보이지 않는 span에 고른 값을 그려 폭을 재고 그 값을 직접 넣는다.
//
// 2) 브라우저가 그리는 화살표는 위치를 바꿀 수 없어 오른쪽 테두리에 붙는다.
//    appearance: none 으로 지우고 같은 선 아이콘을 직접 올려 간격을 잡는다.

const PAD_LEFT   = 14;
const PAD_RIGHT  = 38;  // 화살표가 들어갈 자리
const ARROW_SIZE = 14;
const ARROW_GAP  = 13;  // 화살표와 오른쪽 테두리 사이
// 글꼴 렌더링 차이로 1~2px이 모자라 끝 글자가 잘리는 것을 막는 여유분
const SAFETY     = 4;

const MEASURE = {
  position: "absolute",
  visibility: "hidden",
  whiteSpace: "pre",
  pointerEvents: "none",
  left: -9999,
  top: 0,
};

export default function FitSelect({ value, onChange, options, style, minWidth = 110, ...rest }) {
  const selectRef = useRef(null);
  const measureRef = useRef(null);

  const selected = options.find((o) => String(o.value) === String(value));
  const label = selected ? selected.label : "";

  useEffect(() => {
    const measure = () => {
      const el = selectRef.current;
      const span = measureRef.current;
      if (!el || !span) return;

      // 글꼴이 같아야 폭이 맞는다. font 축약형은 브라우저가 빈 문자열로 주는 경우가 있어
      // 항목을 하나씩 가져온다.
      const css = window.getComputedStyle(el);
      span.style.fontFamily    = css.fontFamily;
      span.style.fontSize      = css.fontSize;
      span.style.fontWeight    = css.fontWeight;
      span.style.fontStyle     = css.fontStyle;
      span.style.letterSpacing = css.letterSpacing;

      // offsetWidth는 정수로 깎여 끝 글자가 잘린다. 소수까지 담는 값을 올림해서 쓴다.
      const text = Math.ceil(span.getBoundingClientRect().width) + SAFETY;
      el.style.width = `${Math.max(minWidth, text + PAD_LEFT + PAD_RIGHT)}px`;
    };

    measure();
    // 한글 웹폰트가 늦게 올라오면 첫 측정이 대체 글꼴 기준이라 어긋난다.
    // 글꼴이 준비되면 한 번 더 잰다.
    document.fonts?.ready.then(measure).catch(() => {});
  });

  return (
    <span style={{ position: "relative", display: "inline-flex", alignItems: "center", maxWidth: "100%" }}>
      <span ref={measureRef} style={MEASURE} aria-hidden="true">{label}</span>
      <select
        ref={selectRef}
        value={value}
        onChange={onChange}
        style={{
          ...style,
          appearance: "none", WebkitAppearance: "none", MozAppearance: "none",
          paddingLeft: PAD_LEFT, paddingRight: PAD_RIGHT,
          maxWidth: "100%",
        }}
        {...rest}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
      {/* 클릭이 select로 그대로 가도록 이벤트를 받지 않는다 */}
      <span style={{
        position: "absolute", right: ARROW_GAP, top: "50%", transform: "translateY(-50%)",
        color: "#9CA3AF", pointerEvents: "none", display: "flex",
      }}>
        <Icon name="chevronDown" size={ARROW_SIZE} strokeWidth={2} />
      </span>
    </span>
  );
}
