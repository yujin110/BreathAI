import { useEffect, useRef } from "react";

// 고른 값의 글자 길이에 맞춰 폭이 줄어드는 드롭다운.
//
// <select>는 폭을 정할 때 "고른 값"이 아니라 "선택지 중 가장 긴 것"을 본다.
// 그래서 width: auto 를 줘도 짧은 값을 골랐을 때 남는 자리가 그대로 남고,
// 화살표만 오른쪽 끝으로 밀려 글자와 동떨어져 보인다.
//
// 보이지 않는 span에 고른 값을 그려 폭을 재고, 그 값을 select에 직접 넣는다.
// 상태를 쓰지 않고 DOM을 바로 고치므로 다시 그리지 않는다.

// 글자 뒤에 화살표와 좌우 여백이 들어갈 자리
const CHROME_WIDTH = 46;

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
    const el = selectRef.current;
    const span = measureRef.current;
    if (!el || !span) return;
    // 글꼴이 같아야 폭이 맞는다. select에 실제로 적용된 값을 그대로 가져온다.
    const css = window.getComputedStyle(el);
    span.style.font = css.font;
    span.style.letterSpacing = css.letterSpacing;
    el.style.width = `${Math.max(minWidth, span.offsetWidth + CHROME_WIDTH)}px`;
  });

  return (
    <>
      <span ref={measureRef} style={MEASURE} aria-hidden="true">{label}</span>
      <select ref={selectRef} value={value} onChange={onChange} style={style} {...rest}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
    </>
  );
}
