# 미답변·오답변 우선 분석: AI 구현 및 검증

2026-11-06. origin/develop dbd8984에서 시작하고 게시 전 최신 develop 2b3f6b9까지 반영한 AI·AI 서버 변경이다. 기능 브랜치는 feat/analysis/target-selection/AI다. BE·FE 소스는 수정하지 않았다. 사용자 제공 연동계약 v3 및 설계 명세 ver2의 AI P0 범위를 구현했다. 원본 문서 파일은 변경하지 않았다.

## 동작

- POST /pipeline에 analysisMode를 추가한다. 생략은 IMPROVEMENT, 명시적 빈 문자열·소문자·미정의 값은 422다. 모드는 작업 메타데이터와 요청 fingerprint에 포함된다.
- IMPROVEMENT는 UNANSWER와 LOW_QUALITY(화면의 오답변)를 모두 선택한다. UNANSWER_ONLY와 ALL도 지원한다. 이전에 접수되어 모드 정보가 없는 대기 작업은 ALL로 실행한다.
- 전체 입력 검증·기간 필터 후 전체 통계를 저장하고 대상을 선택한다. 대상만 번역·마스킹·정규화·임베딩·군집화·FAQ 생성에 사용한다.
- 답변확률 별칭을 AI 직접 업로드에서도 지원한다. 기존 명시적 유형 우선순위와 백분율/ai_percent 단위를 유지한다.
- 전체 통계(systemStatus, usageStats, trend, currentUnanswerRate)는 전체 입력 기준, 군집·FAQ·급증 키워드는 선택된 질문 기준이다. 기존 KB 근거와 검색 판정은 보존한다.
- 완료 콜백에 resultSchemaVersion=2, analysisMode, completionReason, analysisScope를 추가한다. sourceLogSeqNum은 원본 ID를 유지한다.
- 대상 0건, 유효 문장 0건, 소량 생략, FAQ 군집 0개는 구분된 완료 사유와 유효한 빈 배열을 반환한다. 예외는 FAILED다. 실제로 군집화한 노이즈 좌표는 유지하며 생략 행에 좌표를 만들지 않는다.
- 소량 기준은 N < max(3,minClusterSize,minSamples+1)이다. UMAP 이웃 수는 min(15,N-1), 군집 차원은 min(10,N-2)다. 3개 입력의 2차원 UMAP은 spectral 초기화의 고유벡터 제한을 피하도록 random 초기화를 사용한다.
- 원본·manifest·target·전처리·군집 ID와 요약·통계 불변식을 확인한다. 콜백 생성 시 저장된 범위를 실제 산출물에서 재검증한다.

## 진행 및 취소

GET /jobs/{id}의 기존 analysisId/status/callback에 progress 객체를 추가한다. 과거 기록에 진행 데이터가 없으면 null이다. JSON 예시는 tests/fixtures/analysis_v2에 보관했다.

상세 phase는 QUEUED, CLASSIFYING, PREPROCESSING, EMBEDDING, DIMENSION_REDUCTION, CLUSTERING, FAQ_GENERATION, RETRIEVING, ANSWER_PIPELINE, FINALIZING이다. 진행 단위는 LOG/CLUSTER/STEP, 전체 실행 경과는 초 단위이며 대기 시간을 제외한다.

하위 파이프라인은 단계·배치 진행을 원자적 파일 및 stdout 이벤트로 기록한다. 작업 프로세스는 이벤트를 즉시 SQLite에 반영하고, worker 감독 스레드는 5초 간격으로 대기/실행 작업의 heartbeat를 갱신한다. sequence는 저장마다 증가하고 동일 GET 조회만으로 증가하지 않는다. 과거 phase·진행 건수로 역행하지 않는다. heartbeat는 감독 프로세스가 살아 있다는 뜻이며 추론 전진 보장은 아니다.

완료는 FINALIZING 1/1, 실패·취소는 마지막 phase를 보존한다. terminal 상태 이후 진행 정보는 동결한다. 기존 상태 콜백 PREPROCESSING/ANALYZING/COMPLETED/FAILED와 취소 API의 200/404/409/503 계약을 유지한다. 취소 완료 콜백을 새로 만들지 않는다.

## 작업별 산출물

| 파일 | 내용 |
|---|---|
| dataset/tota24_unanswered_2026Q1.csv | 기간 내 전체 입력 |
| dataset/input_statistics.json | 선별 전 전체 유형·일별 통계 |
| dataset/classification_manifest.jsonl | 원본 ID·유형·분류 근거·대상 여부·제외 사유, 질문 본문 없음 |
| dataset/analysis_targets.csv | 대상 질문·확정 logType |
| dataset/preprocessed_full.csv | 대상 전체의 전처리 결과, logType 보존 |
| dataset/analysis_metadata.json | 산출물 버전·정책·모드·실제 군집 설정 |
| dataset/analysis_scope.json | 완료 사유 및 검증할 범위 집계 |
| dataset/unclustered.json | 짧은 문장·소량·노이즈·FAQ 최소 크기 미달 질문. 텍스트는 마스킹 옵션을 따른 정규화 결과 |
| runtime_manifest.json | 모델/리비전 설정·설치 버전·마스킹/번역·군집 설정 |
| progress.json | 마지막 실제 단계 이벤트. 외부 sequence/heartbeat는 SQLite 감독 정보가 기준 |
| performance.json | 단계별 시간·표본 CPU peak RSS·CUDA peak allocated/reserved·생성/수정 시도·LLM 호출/토큰 수·전체 실행/대기 시간·결과 크기 |

코드 버전은 배포 시 TTOBAGI_CODE_VERSION, 모델 리비전은 기존 TTOBAGI_LLM_REVISION/TTOBAGI_JUDGE_REVISION으로 지정할 수 있다. 지정하지 않은 리비전·코드 값은 null이며 고정된 버전이라고 해석하지 않는다. 메모리는 bytes, 시간은 seconds다. CPU peak는 0.2초 표본 측정으로 순간 최고치를 놓칠 수 있다. CUDA peak는 로드된 torch의 allocator 측정이며 장치 전체 VRAM이나 GPU 연산 점유율이 아니다. GPU 미사용 시 null이다. 중첩 단계의 메모리는 합산하지 않는다.

## 검증 결과

- Windows Python 3.12에서 전체 unittest 153개 실행, 실패 0개, POSIX 전용 6개 제외. 실제 HTTP 및 UMAP/HDBSCAN 경계 시험을 활성화했다.
- numpy 2.2.6, scipy 1.15.3, scikit-learn 1.6.1, umap-learn 0.5.9.post2, hdbscan 0.8.40으로 검사했다. Linux/Python 3.11 운영 환경 검증을 대체하지 않는다.
- 혼합 및 오답변만 있는 입력의 선별, 19,000건 중 오답변 950건+미답변 950건 보존, 모드별 fingerprint 충돌, 별칭 우선순위, 0건·소량·노이즈, 산출물 변조 거절, 정상 콜백 fixture 일치, 진행 역행·종료 동결을 확인했다.
- 실제 worker의 대상 0건 처리에서 완료 콜백과 FINALIZING 1/1을 확인했다. 취소 503·상태 경합 기존 회귀 시험을 통과했다.
- UMAP은 N=3/5/12/32, HDBSCAN은 N=5/12/32로 실제 CPU 계산했다. 문장 임베딩·LLM 추론 시험은 아니다.
- 별도 실제 run_job 하위 프로세스 smoke: 전체 정상 또는 오답변 2건만 포함한 200/5,000/19,000건, 총 6회 모두 정상 완료. 대상 0건 및 소량 생략 경로만 검증한 합성 입력이다. 로그·결과는 evaluation/results/analysis-selection-local-20261106에 로컬 보관하며 Git에 포함하지 않는다.

## 남은 연결·부하 검증

관련 이슈는 #168이다. 운영 배포는 수행하지 않았다. 기능 브랜치에서 검토하며 PR 생성·병합은 사용자가 진행한다. BE 저장/진행 전달·FE 안내는 각 담당자의 변경을 사용하며, 배포는 BE → AI → FE 순서를 지킨다. 같은 파일 재사용으로 예전 결과가 반환되면 새 파일 또는 기간·군집 옵션 변경으로 새 분석인지 확인해야 한다.

실제 데이터와 GPU 모델로 ALL/IMPROVEMENT 200/5,000/19,000건 전체 추론 비교, OOM·품질 검수, Linux 프로세스 트리 취소 시험은 남아 있다. GPU 검증 전 전체 처리 시간 50% 단축이나 19,000건 전체 추론 지원을 보장하지 않는다.

실제 비교 실행기는 다음과 같다. 원본을 수정하거나 운영 DB에 결과를 쓰지 않고 새 작업 디렉터리에서 실행하지만, KB 조회 설정과 모델 실행 자원은 필요하다.

```powershell
python -m evaluation.analysis_mode_benchmark --case 200=fixture200.csv --case 5000=fixture5000.csv --case 19000=fixture19000.csv --output evaluation/results/modes-001 --repetitions 4
```

입력 fixture는 별도로 대표 구성비를 맞춰 준비한다. 반복 번호를 콜드/웜 캐시로 자동 간주하지 않는다. 새 프로세스 모델 로딩과 공유 캐시 상태를 통제·기록한 뒤 비교해야 한다. P1 중복 임베딩·공유 KB 캐시·LLM 배치 최적화는 실제 병목 측정 후 진행한다.
