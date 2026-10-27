# 백엔드 API 추가/수정 요청서

최초 작성: 2026-10-10 · 갱신: 2026-10-19 (develop `c984af2` 기준)
작성: 프론트엔드 · 대상: `ttobagi-server`, 일부는 AI 서버(FastAPI) 동반 수정 필요

프론트엔드 신규 요구사항 4건에 대한 요청서입니다. **10/4~10/10 PR 8건이 머지되면서
처음 요청했던 내용 대부분이 이미 해결됐습니다.** 아래는 남은 항목만 정리한 것입니다.

---

## 처리 완료 (확인했습니다, 감사합니다)

| 최초 요청 | 처리된 PR / 커밋 |
|---|---|
| EXPAND 후보 반영 시 기존 FAQ를 갱신 | `b4ea9bb` (PR #25) + `FaqApplyResponse.editType` 추가 |
| FAQ 목록에 `versionId` / `category` / `keyword` 필터 | PR #25 |
| FAQ 버전 목록·상세 조회 | PR #30 |
| FAQ 파일 다운로드 | PR #32 (CSV, `byte[]` + Content-Disposition) |
| 스냅샷 적재 (버전 데이터가 쌓이지 않던 문제) | PR #30 (`FaqVersion` / `FaqVersionItem` 도입) |
| 미구현 라우트가 500으로 응답되던 문제 | PR #34 (`NoResourceFoundException` 처리) |
| 비밀번호 재설정 6자리 코드 | PR #36 |
| `FaqCandidateItem`에 `matchedFaqs` 추가 | PR #25 (`matchedFaqId`, `question`까지 내려와서 중복 검토가 훨씬 쉬워졌습니다) |

프론트는 위 변경에 맞춰 전부 수정했습니다. 다운로드는 CSV 바이너리 응답용 경로를 따로
만들었고(`src/api/client.js`의 `requestFile`), FAQ 목록·이력은 서버 페이징/필터를 쓰도록 바꿨습니다.

---

## ① 기존 학습데이터 조회 API (아직 필요)

**요구사항**: "기존 학습데이터를 화면에서 볼 수 있게"

대상은 또타24 챗봇이 현재 학습하고 있는 **질문-답변 쌍 약 2000건**(`bronze_counselling_info`)입니다.
`TrainingDataListResponse`가 DTO 목록에는 있는데 API 명세서 표에는 행 자체가 없고, 백엔드에도
엔드포인트가 없습니다. PR #37(Bronze 원본 적재)이 적재와 스키마는 올려줬지만 조회 API는 아닙니다.
관련 이슈로 #28(기존 FAQ 적재 자동화)이 열려 있습니다.

2000건 규모라 **서버 페이징과 서버 검색이 필요합니다.**

### 참고: 테이블은 이미 있습니다 (PR #37)

`db/schema/bronze_faq_sources.sql`에 스키마가 정의돼 있어서, 아래 컬럼을 그대로 쓰시면 됩니다.

| Bronze 컬럼 | 응답 필드 | 비고 |
|---|---|---|
| `seq_num` | `seqNum` | 행 식별자 |
| `ci_question` | `standardQuestion` | 대표 질문 문구 |
| `ci_answer0` | `answer` | 기본 답변 본문 (LONGTEXT) |
| `q_type` | `qType` | 카테고리 코드 |
| `bronze_cate_info.q_disp_name` | `category` | `q_type`으로 조인해 표시명 해석 |
| `qa_cnt` | `qaCnt` | 누적 질의 수 |

**주의: PK가 `(_ingest_batch_id, seq_num)` 복합키입니다.** 같은 `seq_num`이 적재 배치마다
중복으로 존재하므로, **최신 배치 기준으로 조회**하지 않으면 같은 질문이 여러 번 나옵니다.
`bronze_ingest_batch`에서 최신 배치를 고르는 조건이 필요합니다.

`state` 컬럼으로 비활성 항목을 제외할지는 백엔드 판단에 맡기겠습니다
(스키마 주석에 "상태값 (확인 필요)"로 적혀 있습니다).

### 카테고리 검색 3건 (2026-10-21 요청)

2026-10-21에 배포본에서 FAQ CSV를 받아 확인한 결과, `q_type`과 `bronze_cate_info`는
정상입니다. CSV의 카테고리 열에 값이 채워져 나옵니다(기타, 홍보관련, 열차운영관련,
승강설비 운영, 역사(편의)시설물, 승차권/운임, 역사내시설물, 부대사업, 공사경영 등).

CSV 내보내기는 `q_type`으로 마스터에서 이름을 찾아오기 때문입니다
(`FaqVersionItemRepository.findExportRows`의 `COALESCE(... bronze_cate_info ...)`).

#### ① 카테고리 부분 일치 — 가장 급합니다

`FaqRepository.findQTypesByCategoryName`이 완전 일치라 정확한 표시명을 모르면
에러 없이 **조용히 0건**이 됩니다.

```diff
- @Query(value = "SELECT DISTINCT q_type FROM bronze_cate_info WHERE q_disp_name = :name", ...)
+ @Query(value = "SELECT DISTINCT q_type FROM bronze_cate_info WHERE q_disp_name LIKE CONCAT('%', :name, '%')", ...)
```

반환 타입이 이미 `List<Integer>`이고 호출부도 `f.qType IN :qTypes`라 **다른 코드는
바꾸지 않아도 됩니다.** 같은 화면의 `keyword` 검색이 이미 `LIKE`라 동작도 통일됩니다.

실제로 **명세서 예시인 `category=열차운영`을 그대로 넣으면 0건입니다.** 실제 값이
"열차운영관련"이기 때문입니다. `역사(편의)시설물`, `승차권/운임`처럼 괄호·슬래시가
든 이름은 정확히 입력하기 더 어렵습니다.

#### ② FAQ 목록 조회의 category도 마스터에서 찾아 주세요

`FaqService.getFaqList`는 `f.getCategory()`로 **저장값을 그대로** 내보냅니다.
배포본 FAQ 1,060건에 카테고리 태그가 하나도 표시되지 않는데, `gold_faq.category`가
비어 있기 때문으로 보입니다(①의 CSV 결과상 `q_type`은 정상).

CSV 내보내기와 같은 규칙으로 `q_type`에서 이름을 찾아 주시면 화면에도 카테고리가
표시됩니다. 기존 데이터를 건드리지 않는 방법입니다.

백필을 택하신다면 아래도 가능합니다.

```sql
UPDATE gold_faq f
SET category = (SELECT c.q_disp_name FROM bronze_cate_info c
                WHERE c.q_type = f.q_type ORDER BY c._ingested_at DESC LIMIT 1)
WHERE category IS NULL AND q_type IS NOT NULL;
```

#### ③ `GET /api/v1/faq/categories` 신규 — 명세서에도 등록 부탁드립니다

카테고리 필터를 **드롭다운**으로 만들려면 목록이 필요합니다. 현재 명세서에 카테고리
목록을 주는 API가 없어, 프론트는 문자열 입력으로 두고 있습니다.

Token: Yes · 권한: `hasAnyRole('USER','ADMIN')`

```json
{
  "success": true,
  "message": "요청에 성공하였습니다.",
  "status": 200,
  "data": {
    "categories": [
      { "qType": 1, "name": "열차운영관련" },
      { "qType": 3, "name": "부대사업" }
    ]
  }
}
```

`bronze_cate_info`에서 `q_type` 기준 최신 `q_disp_name` 하나씩이면 충분합니다
(`findCategoryNameByQType`와 같은 규칙).

**프론트는 이 경로를 이미 호출하도록 해 두었습니다.** 아직 없으므로 실패하면 기존
문자열 입력으로 자동으로 떨어집니다. 구현되면 프론트 수정 없이 드롭다운으로 바뀝니다.

---

### ~~`GET /api/v1/faq/training-data`~~ (철회)

2026-10-19 기준 **철회합니다.** "FAQ 리스트가 곧 기존 학습데이터"라는 정리에 따라
기존 학습데이터 화면을 삭제했습니다. 구현하지 않으셔도 됩니다.


### `POST /api/v1/dashboard/analyze/upload` 파라미터 추가

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `minClusterSize` | int | X | AI 서버 기본값 | HDBSCAN `min_cluster_size` |
| `minSamples` | int | X | AI 서버 기본값 | HDBSCAN `min_samples` |

프론트는 이미 이 두 값을 multipart로 보내고 있습니다(`src/api/dashboard.js`). 빈 값은 아예
보내지 않으니 `required = false`로 받으시면 됩니다.

변경이 필요한 지점:

1. `DashboardController.uploadAndStartAnalysis()` — `@RequestParam` 2개 추가
2. `WebClientUtil.sendPipeline()` — AI 서버 `/pipeline` 호출 바디에 전달
3. `AnalysisJob` — 어떤 파라미터로 돌린 분석인지 재현 가능하게 컬럼 추가 (권장)
4. AI 서버 — `/pipeline`에서 두 값을 받아 HDBSCAN에 넘기고, 미전달 시 기존 기본값 유지

### AI팀 확인 요청 (2026-10-15 기준)

`feat/evaluation/pipeline-integration/AI` 브랜치의 `services/clustering.py`를 봤는데,
클러스터링 파라미터가 **모듈 상수로 고정**돼 있고 `cluster()` 함수가 인자를 받지 않습니다.

```python
HDBSCAN_MIN_CLUSTER_SIZE = 5
HDBSCAN_MIN_SAMPLES = 2
UMAP_NEIGHBORS = 15

def cluster(emb_clu: np.ndarray) -> np.ndarray:   # 인자 없음
```

그래서 백엔드가 값을 전달해도 받을 곳이 없습니다. `cluster(emb_clu, min_cluster_size=None,
min_samples=None)` 형태로 인자화하고 `run_pipeline`에서 넘겨주시면, 백엔드→AI 전달만
연결하면 끝납니다. 미전달 시 지금 상수를 기본값으로 쓰면 기존 동작은 그대로 유지됩니다.

### 더 앞선 선행 조건: `/pipeline` 엔드포인트

`run_pipeline.py`가 argparse CLI(`--full`)로만 실행되고, 저장소 어디에도 FastAPI 앱이
없습니다. Spring `WebClientUtil`이 `POST /pipeline`을 호출하는데 그걸 받을 서버가 없어서,
**업로드 → 분석 흐름이 end-to-end로 동작하지 않습니다.** 이 엔드포인트가 먼저 필요합니다.

프론트 화면에는 이 항목에 "백엔드 반영 대기" 뱃지를 달아뒀습니다. 서버가 받기 시작하면
그 뱃지만 떼면 됩니다.

---

## ③ CORS 허용 오리진 등록 (배포 필수)

프론트엔드를 GitHub Pages로 배포합니다. 배포 주소가 확정됐습니다.
(레포가 `seo-yn/BreathAI` → `yujin110/BreathAI`로 이전되어 주소가 바뀌었습니다.)

```
https://yujin110.github.io/BreathAI/
```

`CORS_ALLOWED_ORIGINS`에 **경로를 뺀 오리진만** 넣어주세요. 로컬 개발용 주소도 함께 필요합니다.

```
CORS_ALLOWED_ORIGINS=https://yujin110.github.io,http://localhost:5173
```

현재 두 오리진 모두 preflight가 403으로 거부됩니다. `SecurityConfig`가
`setAllowCredentials(true)`라 와일드카드(`*`)는 쓸 수 없습니다.

---

## ④ ApiResponse에 에러 식별 코드 노출 (권장)

`ErrorCode`에는 `A-008`, `A-009`, `A-010` 같은 식별 코드가 있고 주석에 "클라이언트 분기용
식별 코드"라고 적혀 있는데, `ApiResponse`에 `code` 필드가 없어서 **응답에 실리지 않습니다.**

그래서 프론트가 비밀번호 재설정 실패를 구분할 때 메시지 문자열을 검사하고 있습니다
(`src/pages/ResetPage.jsx`의 `needsNewCode()` — 429이거나 400 + 메시지에 "만료"/"이미 사용" 포함).
문구가 한 글자라도 바뀌면 이 분기가 깨집니다.

```json
{ "success": false, "message": "인증 코드가 만료되었습니다. ...", "code": "A-009", "status": 400 }
```

`ApiResponse.error(ErrorCode)`에서 `errorCode.getCode()`를 같이 담아주시면, 프론트를
코드 기반 분기로 바꾸겠습니다. 기존 응답에 필드가 추가되는 것뿐이라 호환성 문제는 없습니다.

---

## ⑤ FAQ 변경 이력의 `faqId` 필터 (선택)

`GET /api/v1/faq/history`가 전역 이력만 조회하고 `faqId` 필터를 받지 않습니다. 그래서
FAQ 단건의 변경 이력을 보여주는 UI는 포기하고, **전역 "변경 이력" 탭**으로 만들었습니다
(`editType` 필터 + 페이징, 각 행에 `faqId` 표시). 지금 구조로도 쓸 수 있습니다.

다만 "이 FAQ가 어떻게 바뀌어 왔는지"를 FAQ 상세에서 보여주려면 `faqId` 필터가 필요합니다.
여유 있을 때 `@RequestParam(required = false) Long faqId` 추가를 검토해주세요.

---

## ⑥ 후보 반려 API (선택, 우선순위 낮음)

승인은 `POST /api/v1/faq/apply`가 있는데 **반려에 해당하는 엔드포인트가 없습니다.**
`ReviewStatus.REJECTED`와 `FaqActionLog`가 있는데 이를 기록할 통로가 없습니다.

지금 프론트의 "반려"는 화면 상태만 바꿔서 새로고침하면 사라집니다. 검토자가 같은 후보를
두 번 판단해야 하는 상황입니다.

```
PATCH /api/v1/faq/recommendations/{candidateId}/reject
Request:  { "reason": "운영 정책과 맞지 않음" }   // optional
Response: ApiResponse<Void>
```

---

## 명세서 자체 수정 요청

프론트는 **실제 백엔드 구현을 기준**으로 맞춰져 있습니다. 명세서 쪽이 아직 다른 항목입니다.

| 기능 | 명세서 | 실제 구현 (develop) |
|---|---|---|
| FAQ 수정 이력 | `/api/v1/faq/history?page=&editType=` | 동일 ✅ (10/4에 맞춰졌습니다) |
| 데이터 분석 결과 조회 | `/api/v1/dashboard/analyze/result` | 동일 ✅ |
| 분석 결과 날짜 | `startDate=2026-04-01` | 동일 ✅ (`ISO.DATE`) |
| 엑셀 다운로드 | `ApiResponse<ExcelDownloadResponse>` | **CSV `byte[]` 직접 응답** — 명세서 갱신 필요 |
| 비밀번호 재설정 확정 | `{ token, newPassword }` | **`{ email, token, newPassword }`** — 명세서 갱신 필요 |
| FAQ 질문 필드 | `question` | **`standardQuestion`** — DTO 문서 갱신 필요 |

### 미답변 필드명 안내

`AnalyzeResultResponse`의 미답변 관련 JSON 키가 `unanswered*` → `unanswer*`로 바뀌었습니다
(`unanswerAnalysis`, `samplingStatus.unanswer`, `currentUnanswerRate`, `trend[].unanswerCount`).
프론트는 이에 맞춰 수정했습니다. 다만 Java 타입명은 `UnansweredAnalysis`로 남아 있어
혼동 소지가 있으니, 타입명도 맞추시거나 주석을 남겨두시면 좋겠습니다.

또 `AnalyzeResultResponse.SurgeDetection`에는 AI 콜백에 있는 `increasedRate`와 `keywordCount`가
빠져 있습니다. 급증 키워드를 화면에 띄우려면 이 두 값이 필요합니다.

---

## 이슈 정리 부탁

PR이 머지됐는데 이슈가 열린 채로 남아 있는 것들입니다 — #11, #15, #29, #31, #33.
실제로 작업이 남은 건 #28과 #35뿐입니다.
