// 백엔드는 LocalDateTime을 @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'", timezone="UTC")로
// 내려주고 서버 JVM도 TimeZone.setDefault(UTC)라, 받은 값은 진짜 UTC다.
// 운영자가 보는 값이므로 한국 시간으로 바꿔 보여 준다.
//
// 날짜만 오는 값(FAQ 목록의 createdAt = LocalDate "yyyy-MM-dd")은 시간이 없어
// 변환하면 하루가 밀 수 있다. 그런 값은 그대로 돌려준다.
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

// 자리 수를 맞춘 값을 부분별로 꺼내 쓴다.
// toLocaleString 결과를 문자열 치환으로 다듬으면 "2026-11-01-00:48"처럼
// 날짜와 시각 사이 구분자까지 바뀌어 버린다.
const KST = { timeZone: "Asia/Seoul", hour12: false };

export function formatKst(value, { withSeconds = false } = {}) {
  if (!value) return "-";
  if (DATE_ONLY.test(String(value).trim())) return String(value).trim();

  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);

  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      ...KST,
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit",
    }).formatToParts(d).map((p) => [p.type, p.value])
  );

  const hhmm = `${parts.hour}:${parts.minute}`;
  return `${parts.year}-${parts.month}-${parts.day} ${withSeconds ? `${hhmm}:${parts.second}` : hhmm}`;
}
