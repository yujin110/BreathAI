# API 명세 변경 사항

API 명세서(`API_명세서__DTO.pdf`, 19쪽)와 실제 BE 동작이 다른 부분을 정리한다.
FE·AI 연동은 이 문서의 "실제 동작"을 기준으로 한다.

- 기준 코드: `develop` `d47e1fc` (2026-10-18), 이후 #61 수정(응답 형식·이력 기간·FAQ 카테고리), #64(SSE 8시간), #66(AI 파일별 요약값 저장), #84(계정별 분석 이력·군집화 설정), #86(FAQ 카테고리·FAQ별 수정 이력) 반영
- 확인 방법: 명세서 원문과 코드를 대조하고, 로컬에서 BE를 띄워 모든 API(69건)를 실제로 호출해 응답을 확인했다.
- 명세서와 같은 부분은 적지 않는다. 표의 "결정일"이 비어 있으면 이번 정리에서 처음 기록한 것이다.
- [7. 결정 대기](#7-결정-대기)에 있는 항목은 코드를 고칠지 명세를 고칠지 아직 정하지 않았다.

## 1. 공통

| 항목 | 명세 | 실제 동작 | 결정일 |
|---|---|---|---|
| 응답 형식 | `success`, `message`, `data`, `status` | 같다. 단, `data`가 없으면 `"data": null`을 보내지 않고 **필드를 뺀다** | |
| 본문의 `status` | HTTP 상태 코드 (회원가입 예시 201) | 명세대로 HTTP 상태 코드와 같다. 성공 응답은 회원가입만 201이고 나머지는 200. 예외로 AI 콜백은 명세가 201이지만 200을 유지한다(아래 dashboard 참고) | 2026-10-18 |
| 오류 응답 | 없음 | `{"success": false, "message": "...", "status": 400}`. 입력값 검증 실패(400)만 `data`에 필드별 메시지를 담는다 (예: `"data": {"password": "비밀번호 형식이 올바르지 않습니다."}`) | 2026-10-18 |
| 401 / 403 | 없음 | 401: 토큰이 없거나 유효하지 않음 (`"로그인이 필요합니다."`). 403: 로그인은 했지만 관리자 권한이 없음 (`"ADMIN 계정만 접근 가능합니다."`). 403은 어느 API에서 막혀도 위의 오류 응답 형식과 같다. 토큰 없이 없는 경로를 부르면 404가 아니라 401 | 2026-10-18 |
| 그 밖의 오류 코드 | 없음 | 잘못된 형식·누락 400, 없는 자원 404, 지원하지 않는 메서드 405, 중복 409, 용량 초과 413, 본문 형식 415, 재설정 코드 5회 실패 429 | 2026-10-10 |
| 시각 | FAQ 쪽 예시는 `Z` 없음 (`2026-04-19T17:30:00`) | 모든 시각은 UTC이고 `Z`를 붙인다 (`2026-10-18T04:36:20Z`) | 2026-10-10 |
| 날짜 | 혼재 | `yyyy-MM-dd` | 2026-10-04 |
| 안내 문구 | 예시 문구 | 명세 예시와 문구가 다른 곳이 많다. **FE는 `message` 문구로 분기하지 말고 HTTP 상태 코드를 본다** (`message`는 화면 표시용) | |
| 인증 | Bearer 토큰 | 같다. 토큰 없이 부를 수 있는 API는 회원가입·로그인·로그아웃·토큰 재발급·재설정 요청·재설정 확정, `/actuator/health`, AI 콜백(별도 토큰)뿐이다 | 2026-10-18 |
| 상태 확인 | 없음 | `GET /actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}` (인증 없음, `ApiResponse` 형식 아님) | 2026-10-17 |
| Swagger | 없음 | 배포 서버(prod)에서는 꺼져 있다. `SWAGGER_ENABLED=true`로 켠다 | 2026-10-17 |

## 2. auth

| API | 명세 | 실제 동작 | 결정일 |
|---|---|---|---|
| `POST /auth/signup` | 성공 시 `status` 201, `data: null` | HTTP 201, 본문 `status` 201, `data` 없음(2026-10-18 본문을 201로 맞춤). 이메일은 `@seoulmetro.co.kr`만, 비밀번호는 영문 소문자+숫자+특수문자(`@$!%*?&`) 6~20자이고 **대문자는 쓸 수 없다**. 이미 있는 이메일은 409. `adminCode`가 맞으면 ADMIN으로 가입 | |
| `POST /auth/login` | — | 같다. 비밀번호가 틀리면 401 | |
| `POST /auth/logout` | — | 서버는 아무것도 저장하지 않고 성공만 응답한다. 토큰 삭제는 FE가 한다 | |
| `POST /auth/password/reset` | 예시 이메일 `admin@dongguk.edu` | 이메일은 `@seoulmetro.co.kr`만 받는다 (예시는 오기). 메일로 **6자리 인증 코드**를 보내고, 코드는 30분 동안 유효하다 | 2026-10-10 |
| `POST /auth/password/reset/confirm` | `{ token, newPassword }`, token은 긴 문자열 | **`{ email, token, newPassword }`**. `token`은 메일로 받은 영문 대소문자+숫자 6자리 (예: `aB3xY9`, 대소문자 구분). 코드 불일치·만료·형식 오류 400, 5회 틀리면 코드가 폐기되고 429 | 2026-10-10 |
| `PATCH /auth/promote` | 응답 `ApiResponse<Void>` | 로그인 필요. 응답 `data`에 승격된 사용자 정보(`UserInfoResponse`)가 온다. 관리자 코드가 틀리면 403 | |
| `GET /auth/me` | — | 같다. 토큰이 없으면 401 | 2026-10-18 |
| `PATCH /auth/me` | — | 같다 (비밀번호만 변경). 토큰이 없으면 401 | |
| `DELETE /auth/quit` | — | 로그인 필요 | |
| `POST /auth/reissue` | 응답 `accessToken`, `refreshToken`, `tokenType` | 같은 필드에 `accessTokenExpiresAt`, `refreshTokenExpiresAt`가 더 온다. 리프레시 토큰이 유효하지 않으면 401 | |

## 3. dashboard

| API | 명세 | 실제 동작 | 결정일 |
|---|---|---|---|
| `GET /dashboard/usage` | — | **사용하지 않는다.** 챗봇 서비스 전체 질문 자료가 없어 값은 항상 0이다(API는 남겨 둔다). 업로드 파일의 날짜별 건수는 분석 결과의 `usageStats`로 본다 | 2026-10-18 |
| `GET /dashboard/analyze/result` | 예시 `startDate=2026 04 01`, `trend[].date`는 `"03-20"` | 날짜 파라미터는 `yyyy-MM-dd`. 둘 다 생략하면 가장 최근 완료 분석을 보여 준다. **`scope`(새 파라미터)**: `mine`(기본)은 내 분석 이력에 남아 있는 분석만, `all`은 이력 삭제와 관계없이 완료된 분석 전체를 쓴다(대소문자 무관, 그 외 값 400). 실패한 분석은 어느 쪽이든 쓰지 않는다. 그 기간에 완료된 분석이 없으면 404. **`trend[].date`는 `"2026-03-20"`(yyyy-MM-dd)** | 2026-10-04 |
| (위 응답) `faqCandidates[]` | `category`만 있음 | `candidateType`, `qType`, `category`, `similarQuestions`(클러스터의 실제 질문들)가 함께 온다 | |
| (위 응답) `systemStatus`, `performanceMetrics` | AI 분석 결과 값 | **AI가 보낸 업로드 파일별 값을 그대로 쓴다.** `totalLogCount`는 업로드 전체 행 수(좌표 수와 다를 수 있음), `currentUnanswerRate`는 개선 전 미답변율(미답변 ÷ 전체 × 100, 소수 2자리). 여러 파일을 합칠 때는 건수를 더한 뒤 같은 공식으로 다시 계산한다. AI가 `null`로 보내는 `predictedAccuracyGain`·`resolvedCountByAI`는 `0`으로 나온다. 요약값이 없는 예전 분석은 좌표 수로 센다 | 2026-10-18 |
| (위 응답) `usageStats` (새 필드) | 없음 | 업로드 파일의 날짜별 건수 `[{statDate, totalCnt, answerCnt, lowQualityCount, unanswerCnt}]`(날짜 오름차순). 여러 파일은 날짜별로 더한다. 예전 형식 분석은 `totalCnt`·`answerCnt`가 `null`. `trend`도 같은 자료에서 만든다 | 2026-10-18 |
| (위 응답) `burstKeywords` | — | `surgeDetection`을 요약한 것. 처음 나온 키워드(`surgeDetection[].isNew = true`)는 증가율이 없어 `increasedRate`가 `0`으로 나온다 | 2026-10-18 |
| `GET /dashboard/analyze/{analysisId}/result` | — | 위와 같고, 맨 위에 `status`(분석 상태)가 더 온다 | 2026-10-04 |
| `POST /dashboard/analyze/upload` | `file: logs.xlsx` | **관리자만**(일반 사용자 403). 파일은 **`.xlsx`, `.csv`만**(그 외 400), 빈 파일 400, 최대 50MB(넘으면 413). `periodStartDate`·`periodEndDate`(yyyy-MM-dd)는 생략 가능, `isMaskingEnabled` 기본 true, `isTranslationEnabled` 기본 false. **군집화 설정 `minClusterSize`(2 이상)·`minSamples`(1 이상)를 선택으로 받는다**(범위 밖이면 400, 생략하면 AI 기본값 5·2). 일반 사용자는 업로드할 수 없으므로 내 이력이 비어 있다 | 2026-10-26 |
| (위 응답) | `status: "PREPROCESSING"` | 처음 상태는 **`PENDING`**. `message`가 더 오고, `submittedAt`은 `Z`를 붙인다. 같은 파일을 다시 올리면 새로 분석하지 않고 기존 분석을 돌려준다(오류 아님, `message`가 "동일한 파일의 분석 결과가 이미 존재합니다."). 단 **분석 기간·마스킹·번역·군집화 설정 중 하나라도 다르면 새로 분석**하고, 같은 옵션의 최근 분석이 실패했어도 새로 분석한다. 누가 올렸는지는 따지지 않으며, 어느 경우든 요청한 사람의 분석 이력에 남는다 | 2026-10-26 |
| (위 동작) | 없음 | 업로드한 원본은 Object Storage에 보관하고 위치를 DB에 기록한다. 응답에는 영향이 없다 | 2026-10-18 |
| `POST /dashboard/analyze/callback/{analysisId}` | 인증 없음, 응답 `status` 201 | AI 서버 전용. **`Authorization: Bearer <TTOBAGI_CALLBACK_TOKEN>` 필요**(없거나 틀리면 401). 응답은 HTTP·본문 모두 200. AI 서버가 응답 코드를 어떻게 판단하는지(재전송 등) 확인하기 전이라 명세의 201로 바꾸지 않는다. 본문 형식은 [5. BE↔AI](#5-beai-연동-명세에-없음) 참고 | 2026-10-18 |
| `GET /dashboard/analyze/{analysisId}` | `progress`(선택) | `progress`는 항상 `null`(AI가 진행률을 보내지 않음). 없는 분석은 404 | |
| `GET /dashboard/analyze/stream/{analysisId}` | `event:status`, `data:{status, message}` | 같다. 단 **토큰이 필요**하다. 브라우저 기본 `EventSource`는 헤더를 못 붙이므로 헤더를 붙일 수 있는 방식으로 연결해야 한다. 연결하자마자 현재 상태를 한 번 보내고, **20초마다 빈 신호(SSE 주석 `:heartbeat`)**를 보낸다. 완료·실패한 분석은 상태를 보내고 바로 끝낸다. 연결은 **최대 8시간**(분석이 오래 걸리므로 2026-10-18에 1시간에서 늘림). 그 전에 끊기면 다시 연결하면 되고, 다시 연결하면 현재 상태부터 받는다. 같은 분석을 여러 명이 동시에 구독할 수 있다 | 2026-10-18 |
| `GET /dashboard/analyze/history` | — | `page`(기본 0), `size`(기본 10). **`scope`**: `mine`(기본)은 내 이력만, `all`은 모든 사람의 이력. **`status`**(선택): `COMPLETED`, `FAIL` 등 그 상태만(대소문자 무관). 잘못된 `scope`·`status`는 400. 요청 시각 최신순이며, 같은 분석을 여러 사람이 요청했으면 `all`에서 사람마다 한 줄씩 나온다. 항목에 `requestedBy`(요청자 이메일), `requestedAt`(마지막 요청 시각), `isMine`, `isMaskingEnabled`, `isTranslationEnabled`, `minClusterSize`, `minSamples`(`null`이면 AI 기본값)가 더 온다. 결과·후보·상태 조회는 분석 번호만 알면 누구나 할 수 있다. `period`는 기간이 있으면 `"2026-08-01 ~ 2026-08-31"`, 시작일만 있으면 `"2026-08-01 ~"`, 종료일만 있으면 `"~ 2026-08-31"`, 둘 다 없으면 `null` | 2026-10-26 |
| `DELETE /dashboard/analyze/history/{analysisId}` (새 API) | 없음 | 내 이력에서 그 분석 줄만 지운다(목록 정리용). 분석 결과·후보·반영된 FAQ와 다른 사람의 이력은 그대로이고, `scope=all` 대시보드에도 영향이 없다. 내 이력에 없으면 404. 진행 중인 분석도 지울 수 있다(분석은 계속 진행). 응답 `data` 없음 | 2026-10-26 |

## 4. faq

| API | 명세 | 실제 동작 | 결정일 |
|---|---|---|---|
| `GET /faq` | — | `category`는 카테고리 이름이며 **이름 일부만 맞아도 찾는다**(예: `열차운영` → `열차운영관련`, 2026-10-26). 일부가 겹치는 다른 카테고리도 함께 걸린다. 마스터에 없는 이름이면 결과 0건. `keyword`는 질문·답변·키워드 검색. `versionId`를 생략하면 현재 운영 FAQ를 보여 주고 `versionId`·`versionName`은 `null`. 항목마다 `category`가 더 온다. **저장된 카테고리 이름이 비어 있으면 카테고리 코드(`qType`)로 마스터에서 찾은 이름을 보여 준다**(버전별 목록도 같음, 2026-10-26) | 2026-10-04 |
| `GET /faq/{faqId}` | — | `category`가 더 온다(비어 있으면 목록과 같이 마스터 이름). 없는 FAQ는 404 | 2026-10-26 |
| `GET /faq/categories` (새 API) | 없음 | 카테고리 필터 선택지. 로그인한 사용자 누구나. `{ categories: [{ qType, name }] }`, 카테고리 마스터(`bronze_cate_info`)에서 코드마다 가장 최근에 적재된 이름 하나씩, `qType` 순. 사용 상태(`state`)로는 거르지 않는다 | 2026-10-26 |
| `PATCH /faq/{faqId}` | — | 관리자만. `standardQuestion`, `answer`는 필수(없으면 400). 응답은 명세대로 `question` 이름을 쓴다. `qType`을 바꾸면 `category`도 카테고리 마스터의 이름으로 바뀐다(마스터에 없는 코드면 `null`). 같은 `qType`으로 다시 보내면 `category`는 그대로 | 2026-10-18 |
| `DELETE /faq/{faqId}` | — | 관리자만. 실제로 지우지 않고 비활성 처리하며, 이력에 `DELETE`로 남는다 | |
| `GET /faq/history` | — | `editType`은 `CREATE`, `EXPAND`, `MANUAL`, `DELETE` 중 하나. **`faqId`(새 파라미터, 선택)**를 주면 그 FAQ의 이력만 보여 준다(삭제된 FAQ 포함, 없는 FAQ면 빈 목록). 둘 다 줄 수 있다 | 2026-10-26 |
| `GET /faq/versions`, `/faq/versions/{versionId}` | — | 같다 (시각만 `Z`) | 2026-10-10 |
| `GET /faq/download` | 엑셀(`.xlsx`), `faq_v3_20260831.xlsx` | **CSV**(`text/csv; charset=UTF-8`, 엑셀에서 한글이 깨지지 않게 UTF-8 BOM). 파일 이름 `faq_v{버전번호}_{다운로드한 날 yyyyMMdd}.csv`. 열은 `ID, 카테고리, 질문, 답변, 키워드`(키워드는 쉼표로 이어 씀, 유사질문 제외). 답변은 DB 내용(HTML) 그대로. `ApiResponse`가 아니라 파일 자체가 응답이다 | 2026-10-05 |
| (위 동작) | — | `versionId`를 생략하면 마지막 버전 이후 바뀐 것이 있을 때 **새 버전(DOWNLOAD)을 만들고** 그 버전을 내려준다. 바뀐 것이 없으면 마지막 버전, 버전이 하나도 없으면 첫 버전을 만든다. 로그인한 사용자는 누구나 다운로드할 수 있다 | 2026-10-05 |
| `GET /faq/recommendations/{analysisId}` | `matchedFaqs[]`는 `matchedFaqId`, `question`, `matchScore` | `clusterLabel`, `category`, `similarQuestions`가 더 온다. `matchedFaqs[]`에는 `matchedFaqSeqNum`(AI가 보낸 원본 번호)이 더 온다 | |
| `POST /faq/apply` | — | 관리자만. 요청에 `clusterLabel`을 더 보낼 수 있다(선택). 이미 반영한 후보는 409. 응답 `editType`은 신규면 `CREATE`, 기존 FAQ 확장이면 `EXPAND`. `qType`을 생략하거나 후보와 같게 보내면 후보의 카테고리를 쓰고, 다른 코드로 바꾸면 카테고리 이름을 마스터에서 다시 찾는다(FAQ 수정과 같은 규칙) | 2026-10-18 |

## 5. BE↔AI 연동 (명세에 없음)

| 방향 | 내용 | 결정일 |
|---|---|---|
| BE → AI | `POST {TTOBAGI_AI_SERVER}/pipeline`, multipart. `Authorization: Bearer <TTOBAGI_AI_API_TOKEN>`. **xlsx는 BE가 첫 시트를 UTF-8 CSV로 바꿔서 보낸다**(AI는 CSV만 받음). 엑셀 날짜 칸은 화면 서식과 상관없이 ISO 형식(`2026-08-27 12:04:08`, 시각이 없으면 `2026-08-27`)으로 바꾼다(2026-10-18, AI는 ISO 날짜만 받음). AI가 입력 검사에서 422로 거절하면 분석 실패 사유에 "파일 내용을 받지 않았습니다…"를 남겨 연결 실패와 구분한다(실패 사유는 DB에만 남고 API로는 아직 보내지 않음). DB에 분석 기록이 저장된 뒤에 보낸다. 연결 실패·4xx·5xx·2분 무응답이면 분석을 `FAIL`로 바꾸고 SSE로 알린다. 업로드에 군집화 설정이 있으면 `minClusterSize`·`minSamples`를 함께 보내고, 없으면 보내지 않는다(2026-10-26) | 2026-10-17 |
| AI → BE | `POST /api/v1/dashboard/analyze/callback/{analysisId}`, `Authorization: Bearer <TTOBAGI_CALLBACK_TOKEN>` | 2026-10-18 |
| 콜백 `status` | `PREPROCESSING`, `ANALYZING`(중간 상태, 상태만 바꿈), `COMPLETED`(결과 저장), `FAIL` 또는 `FAILED`(실패, `errorMessage` 저장) | 2026-10-17 |
| 콜백 본문 | 명세의 `clusters`, `unansweredStats`, `surgeDetections`, `performance`는 실제와 다르다. 실제 필드는 `status`, `errorMessage`, `systemStatus`, `performanceMetrics`, `trend`, `burstKeywords`, `unanswerAnalysis`, `clusteringView`, `faqCandidates`, `evaluation`, `surgeDetection`, `usageStats`이고, 구조는 분석 결과 조회 응답과 거의 같다. `trend[].date`는 `MM-dd`도 받으며, BE가 분석 기간의 연도를 붙여 저장한다. 그 밖의 필드(`metricsScope`, `visualizedLogCount` 등)는 무시한다 | |
| 콜백 값의 범위 | — | 값은 모두 **업로드 파일(분석 입력) 범위**다(AI 계약 문서 `docs/dashboard-callback-metrics.md`). BE는 `systemStatus`·`performanceMetrics`를 분석별로, `usageStats`를 분석·날짜별로 저장하고, 서비스 전체 사용량 테이블(`gold_usage_stat`)에는 쓰지 않는다. `usageStats`가 없는 예전 형식은 `trend`로 저품질·미답변 건수만 저장한다. 유형별 합이 `totalLogCount`와 다르면 거절하지 않고 서버 로그에 경고만 남긴다 | 2026-10-18 |
| 알아 둘 점 | — | 업로드 파일에 `logType` 칸이 없으면 AI는 전부 미답변으로 센다. 미답변 데이터 파일을 올리면 미답변율이 100%로 나오는 것이 정상이다. 콜백의 `evaluation`은 서비스 전체 사용량 테이블에 그 날짜 행이 있을 때만 저장되므로, 지금은 저장되지 않는다(AI도 보내지 않음) | 2026-10-18 |
| 빈 후보 | `faqCandidates` 중 `standardQuestion`이나 `answerDraft`가 비었거나 공백뿐인 후보는 저장하지 않고 서버 로그에 경고만 남긴다(나머지는 저장) | 2026-10-26 |
| 중복 콜백 | 이미 완료·실패한 분석에 온 콜백은 저장하지 않고 200을 응답한다(AI가 다시 보내지 않도록) | 2026-10-17 |

## 6. DB 설계

Flyway V6(2026-10-18)·V7(2026-10-26)에서 바뀌었다. 팀 ERD는 V6까지 반영됐고(2026-10-19), V7은 아직 반영 전이다.

- `gold_analysis_job`: `total_log_count`, `correct_count`, `low_quality_count`, `unanswer_count`, `current_unanswer_rate`, `predicted_accuracy_gain`, `resolved_count_by_ai` 추가 (모두 빈 값 허용)
- `gold_analysis_daily_stat` 새 테이블: 분석·날짜별 `total_cnt`, `answer_cnt`, `low_quality_count`, `unanswer_cnt` (`analysis_id` + `stat_date` 고유)
- 분석 행을 지울 때는 `gold_analysis_daily_stat`의 해당 행을 먼저 지워야 한다 (외래키)
- V7 `gold_analysis_history` 새 테이블: 사용자별 분석 요청 이력(`user_id` + `analysis_id` 고유, `requested_at`). 기존 분석은 업로더를 요청자로 채웠다(탈퇴로 업로더가 비어 있으면 없음). 회원 탈퇴 시 그 사람의 행을 지운다
- V7 `gold_analysis_job`: `min_cluster_size`, `min_samples` 추가 (빈 값 = AI 기본값)
- 분석 행을 지울 때는 `gold_analysis_history`의 해당 행도 먼저 지워야 한다 (외래키)

## 7. 결정 대기

명세와 다르지만 코드를 고칠지 명세를 고칠지 아직 정하지 않은 것. 정하면 위 표에 결정일을 적고 여기서 지운다.

현재 없음. (2026-10-18 `systemStatus` 계산 방식은 #66에서 AI 값을 그대로 쓰도록 정리했다)
