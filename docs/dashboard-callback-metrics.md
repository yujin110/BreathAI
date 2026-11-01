# 포인트·분석 통계 콜백 (2026-10-18)

AI 기능 브랜치: `feat/dashboard/analysis-callback/AI`.
BE 수신·저장 반영은 [이슈 #66](https://github.com/yujin110/BreathAI/issues/66)에서 역할별로 진행한다.
이 브랜치에는 AI 코드·테스트·필드 계약만 포함하고 운영 배포는 별도다.

## BE 전달용 확정 계약

1. `systemStatus.totalLogCount`는 선택한 분석 기간에 포함된 업로드 행 전체다.
   기간을 지정하지 않으면 업로드 전체다. 군집화 제외 행·노이즈도 포함한다.
   `samplingStatus.correct + lowQuality + unanswer = totalLogCount`가 항상 성립한다.
   points 개수는 좌표가 있는 행수라 total과 다를 수 있다.
2. `performanceMetrics.currentUnanswerRate`는 업로드 원본의 **개선 전** 미답변율이다.
   `unanswer / totalLogCount * 100`, 0~100 숫자(예: 15% → 15.0)로 보낸다.
   FAQ 생성만으로 원본 logType이나 이 비율을 변경하지 않는다.
   미답변 전용 입력에 logType, keywordMatchPercent, ai_percent가 모두 없으면 계약상 전부 UNANSWER라 100%이며,
   정상 답변까지 포함한 전체 서비스 미답변율은 이 입력만으로 산출할 수 없다.
3. `usageStats`는 `statDate`(yyyy-MM-dd), `totalCnt`, `answerCnt`,
   `lowQualityCount`, `unanswerCnt`를 보낸다. 입력이 있는 날짜만 오름차순으로 보낸다.
   날짜별 `totalCnt = answerCnt + lowQualityCount + unanswerCnt`이며,
   모든 날짜의 totalCnt 합은 systemStatus.totalLogCount와 같다.
4. `burstKeywords`와 `surgeDetection`은 **같은 탐지 결과/키워드 목록**이다.
   전자는 요약(keyword, increasedRate, count), 후자는 상세(isNew,
   relatedClusterLabel, periodStats 등을 추가)다.
   같은 키워드의 count=keywordCount이고 increasedRate도 동일하다.
   비교 기간과 선정 기준은 아래 계산 기준과 같다. 비교 불가이면 둘 다 빈 배열이다.

## 계산 기준

- `metricsScope=ANALYSIS_INPUT`: 기간 필터 후 이번 업로드가 분모다. 서비스 전체 통계가 아니다.
- `sourceLogIdScope=UPLOAD_LOCAL`: CSV 번호 → 전처리/군집 logId → points.sourceLogSeqNum.
  원본 chatting_log 양식은 업로드 시 seq_num→번호, msg_m→질문, sysdate→날짜로 자동 정규화하고 kind=1만 사용한다.
  UTF-8/CP949 CSV 및 기존 XLSX 업로드 모두 별도 변환 도구 없이 처리한다.
  Bronze 전역 PK라고 가정하지 않는다. BE는 analysis→cluster→point 구조로 저장하며 원본 번호에 외래키가 없다.
  같은 업로드의 번호 중복·INT 범위 초과·잘못된 날짜/logType은 접수 단계에서 422로 거절한다.
- `points`: 군집화 좌표 전부, 샘플링 없음. 노이즈(-1)와 해당 클러스터 메타데이터도 포함한다.
  텍스트는 전처리 결과로 마스킹 옵션을 따른다. 사전 필터 제외 행에 가짜 좌표를 만들지 않는다.
- `systemStatus.totalLogCount`: 전체 입력 행수. `samplingStatus`의 유형별 합과 같다.
  `visualizedLogCount`, `excludedLogCount`, `isSampled=false`로 시각화 범위를 구분한다.
  예: 입력 60행, 짧은 문장 제외 7행이면 total=60, points=53, excluded=7.
- `samplingStatus`: 명시적 logType(CORRECT/LOW_QUALITY/UNANSWER)이 있으면 사용한다.
  없거나 빈 값이면 `keywordMatchPercent`(0~100 백분율)로 분류한다.
  25 미만 UNANSWER, 25 이상 75 미만 LOW_QUALITY, 75 이상 CORRECT다.
  keywordMatchPercent가 없고 ai_percent가 있으면 원본 정수를 100으로 나눠 같은 기준으로 분류한다.
  세 속성이 모두 없으면 기존 미답변 업로드 계약에 따라 UNANSWER로 집계한다.
  매칭률 열이 있지만 값이 비었거나 잘못된 경우에는 접수 단계에서 거절한다.
  질의 유효/무효·답변확률·검색 유사도를 답변 성공 여부로 재해석하지 않는다.
  원본 `ai_percent=9999`는 확인된 정의에 따라 99.99%다.
  원본 로그 변환 절차와 입력 예시는 [매칭률 분류 계약](keyword-match-classification.md)을 따른다.
- `performanceMetrics.currentUnanswerRate`: 입력 미답변 건수 / 전체 입력 행수 × 100, 백분율(0~100).
  미답변 파일만 입력하면 100%다. 서비스 전체 실시간 미답변율이나 도입 후 예측값이 아니다.
  predictedAccuracyGain/resolvedCountByAI는 전후 평가가 없어 null이다.
- `usageStats`: 전체 입력을 ISO 날짜별 totalCnt/answerCnt/lowQualityCount/unanswerCnt로 집계한다.
  trend도 동일 집계다. 전역 gold_usage_stat에 일부 업로드로 덮어쓰지 않고 분석별 스냅샷에 저장한다.
  분석/파일별 조회에 usageStats를 추가한다. 월별 서비스 사용량 API는 기존 별도 운영 통계를 사용한다.
- `burstKeywords`와 `surgeDetection`: 업로드 마지막 날짜 기준 최근 7일(BASE) 대 직전 7일(PREV).
  군집 키워드 포함 로그 수를 센다(한 로그의 반복 단어는 1회).
  최근 3건 이상, 이전 대비 2배 이상이면 급증이다.
  비교 기간의 입력 자체가 없으면 비교 불가로 빈 배열을 보낸다.
  이전 키워드 건수가 0이면 isNew=true, increasedRate=null이다.
  기간·최소 건수·상승 기준은 이번 구현의 기본 정책이며 팀 합의 후 조정 가능하다.
  전역 과거 DB 조회가 아니므로 AI 계정에 추가 통계 테이블 권한은 필요 없다.

## BE 연결 및 적용

- 기존 BE는 요약 필드를 무시하므로 AI와 BE 모두 변경해야 한다.
- BE의 저장 컬럼 및 Flyway 마이그레이션은 #66의 BE 구현을 따른다.
  별도 로컬 참고 구현은 JSON 스냅샷 방식으로 검증했으나 이 브랜치에는 포함하지 않는다.
  AI 계정이 BE 요약 테이블을 직접 쓰거나 DDL을 실행하지 않는다.
- BE는 완료 콜백의 전체 건수·유형별 합계·개선 전 미답변율을 전달된 값으로 보존해야 한다.
  points 수로 totalLogCount를 다시 계산하거나 개선 후 평가값으로 미답변율을 바꾸지 않는다.
- 업로드 일부를 전체 서비스 사용량으로 덮어쓰지 않도록 ANALYSIS_INPUT 범위를 구분한다.
  예전 누락 데이터를 가짜 값으로 소급 생성하지 않는다.
- 배포 순서: #66의 BE 수신·저장 변경 → AI 코드 → 새 analysisId로 시험.
  기존 API/worker·DB·토큰·터널 설정을 유지하고 실행 중 작업은 강제 중단하지 않는다.
- 검증: total=유형별 합=일별 total 합, points 수=visualizedLogCount,
  sourceLogSeqNum=해당 파일 번호, 텍스트 마스킹 및 노이즈 저장·조회 확인.
- 로컬 검증과 실제 Vast GPU/NCP 운영 연동 검증은 구분한다.

## 검증 결과

- Python 전체 60개 통과: 모든 좌표/노이즈, 마스킹, 미답변 기본 계약,
  명시적 유형별 건수·일별 합계, 급증 비교, 입력 중복/날짜 오류 및 기존 콜백 회귀.
- 별도 로컬 BE 참고 구현에서 Docker MariaDB + BE HTTP 콜백 테스트 7개 통과(인증 2 + 영속성 5).
  V6 적용, 전체 3건/좌표 2건의 구분 보존, 미답변율·일별 통계 조회,
  노이즈 포인트 저장, 전역 사용량 오염 방지, 중복 콜백 방지,
  합계 불일치 시 완료 상태/요약을 저장하지 않는 동작을 확인했다.
- 위 BE 테스트는 #66의 최신 BE 코드에 대한 검증을 대신하지 않는다.
- 실제 Vast GPU 전체 분석·NCP 터널 경유 운영 연동은 실행하지 않았다.
