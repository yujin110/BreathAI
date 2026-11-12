# 군집화·FAQ 실제 실행 검증 (2026-11-07)

## 검증 조건

- 코드: 현재 BreathAI 작업 트리. 운영 배포 및 운영 DB 쓰기 없음.
- Linux GPU 컨테이너, NVIDIA RTX 2060 6GB, WSL RAM 약 8GB.
- PyTorch 2.8.0+cu128, Transformers 4.57.1, bitsandbytes 0.48.2.
- 실제 모델: KoSimCSE-roberta, BGE-M3, BGE-reranker-v2-m3, Llama 3.1 8B Instruct.
- 8B 모델은 4비트 양자화와 CPU 오프로딩을 사용한다. 답변 평가 Cross-Encoder는 CPU, 검색 batch size는 1이다. 이 조건의 시간은 운영 GPU 성능 수치로 해석하지 않는다.
- 별도 MariaDB에 실제 원천 FAQ 6건을 복사했다. 분석 프로세스는 SELECT 권한만 있는 검증 전용 계정으로 접근한다. 파일 FAQ는 이 실행에서 제외하여 테스트 KB를 6건으로 고정했다.

## 데이터

원본 `미답변 데이터(2026년 1월~8월)_202608271715.xlsx`에서 분실물 관련 고유 질문 8개, 냉방/추위 관련 고유 질문 5개를 선택했다. 동일 질문의 역매핑 검사를 위해 첫 분실물 질문을 3행 추가하여 입력 16건을 만들었다. 원문 출처와 원본 ID는 fixture_manifest.json에 보존하고 검증 입력 ID는 업로드 내 로컬 번호로 부여했다.

현재 분류 정책으로 원본의 유효 점수 질문 4,396건은 모두 UNANSWER였다. 따라서 실제 모델 실행의 입력은 UNANSWER이며, LOW_QUALITY/CORRECT 선택 검증은 별도 회귀 테스트에 포함된다. 정답 라벨을 갖춘 대표 표본이나 실제 19,000행 실행은 아니다.

## 실행 중 발견하여 수정한 문제

1. 신규 IntentVerifier가 필수 모델 이름 인자 없이 load_chat_model을 호출하고 있었다. 예외를 unknown으로 바꾸기 때문에 기존 모의 테스트로는 병합 기능이 사실상 비활성화되는 문제를 놓칠 수 있었다. 설정된 model_id()를 전달하도록 수정하고 실제 함수 시그니처를 검사하는 autospec 회귀 테스트를 추가했다.
2. 실제 8B 모델이 같은 분실물 문의를 문장 형식 차이 때문에 다른 의도로 판정했다. 공통 안내 답변으로 양쪽 요구를 해결할 수 있는지를 기준으로 삼고, 상황 진술/해결 요청의 표현 차이와 실질적인 절차 차이를 구분하도록 프롬프트를 보완했다. 첫 실패 결과는 intent_results.initial.json에 보존했다.
3. 로컬 모델 경로를 명시할 수 있도록 임베딩/검색/재정렬 모델의 환경변수 설정을 추가했다. 기본 모델은 변경하지 않았다.

## 완료된 검사

- 실제 컨테이너에서 회귀 테스트 169개 전부 통과. `TTOBAGI_TEST_HTTP=1`, `TTOBAGI_TEST_CLUSTERING=1`로 선택 검사를 활성화하여 건너뛴 항목이 없다. 워커 프로세스·취소, 실제 HTTP multipart, 실제 UMAP/HDBSCAN의 3/5/12/32건 경계 검사가 포함된다.
- 실제 Llama 8B 판정 3개 사례 통과: 같은 냉방 감소 의도, 냉방 증가/감소의 반대 의도, 같은 분실물 문의. 이는 수정 확인용 사례이며 독립적인 모델 정확도 측정이 아니다.
- 실제 HTTP 업로드가 202로 접수되고, QUEUED → RUNNING으로 전이했다.
- 실제 전처리·임베딩·UMAP·HDBSCAN·역매핑을 실행하여 고유 질문 13개, 원본 16건, 군집 2개를 생성했다. 실제 PNG 시각화도 저장했다.

## 전체 실행 결과

실제 HTTP 업로드 → QUEUED → RUNNING → 전처리 → 임베딩 → 군집화 → FAQ 생성 → 검색/재정렬 → 답변/평가/정제 → FINALIZING → COMPLETED까지 완료했다. 관측 시간은 1,257.38초(약 20분 57초)다. 테스트용 CPU 작업도 일부 병행했으므로 성능 비교 실험 수치로 사용하지 않는다.

| 검사 | 결과 |
| --- | --- |
| API 인증 없는 요청 거절 | 401 확인 |
| 정상 업로드 | 202 확인 |
| 완료 상태·진행 정보 | COMPLETED / FINALIZING 1/1 |
| 동일 파일·옵션 재업로드 | duplicate=true, 재실행 없음 |
| 원본 로그 / 고유 질문 / 군집 | 16 / 13 / 2 |
| 원본 로그 누락 / 노이즈 | 0 / 0 (이 소규모 입력에 한함) |
| 동일 정규화 질문의 최종 군집 | 전부 일치 |
| FAQ 생성 샘플 중복 | 없음 |
| FAQ 후보 | 2건, NEW 2건 |
| 신규 단계의 런타임 예외 근거 | 0건 |
| 최종 콜백 저장 내용과 산출물 재생성 | 일치 |

최대 프로세스 RSS 약 5.90GiB, PyTorch 최대 GPU 할당 약 4.48GiB/예약 약 5.37GiB를 관측했다. 운영 환경에서의 VRAM 요구량을 보증하는 값은 아니다.

검증 KB 6건에는 생성된 표준 질문을 직접 해결하는 근거가 부족했다. 재정렬 최고 점수는 약 0.0013 / 0.0036이므로 답변 근거 채택 기준과 EXPAND 기준에 미달했다. 두 후보는 NEW로 남고, 답변은 근거 부족 안내이며 모두 검토 필요로 표시됐다. 이 실행은 근거 없는 안내를 생성하지 않는 경로를 확인한 것이며, 유용한 FAQ 답변 2건을 확보했다는 의미가 아니다.

EXPAND와 충분한 근거를 사용하는 답변의 성공 경로는 별도 `positive-match` 컴포넌트 실행으로 추가 확인했다. 동일한 실제 검색·재정렬·의도 검증·답변 생성 함수를 사용했으며 추론 결과를 모의 처리하지 않았다.

| 추가 검사 | 결과 |
| --- | --- |
| 원천 FAQ와 정확히 같은 질문 검색 | 기대 FAQ가 1위 |
| 검색 / 재정렬 점수 | 0.8169204 / 0.9999515 |
| 운영 FAQ 판정 | EXPAND / CONFIRMED |
| 대상 ID | 기대한 검증용 FAQ ID 900004 일치 |
| 같은 결과에서 운영 ID 제거 | NEW, 근거 ID_LINK_ERROR, 자동 확장 차단 |
| 실제 근거 기반 답변 | 생성 성공, 근거 2개 사용, 1회 시도 |
| 답변 검증 누락 항목 | 없음 |
| 근거 부족/충돌용 보류 문구 | 없음 |

이는 실행 성공과 기본 안전장치의 확인이다. 전체 데이터의 의미 병합 정확도, 확장 정확도 95%, 채용률 75% 또는 원천 FAQ 내용의 최신성 검증을 의미하지 않는다. 19,000행 및 운영 BE·FE와 연결한 배포 환경 실행은 수행하지 않았다.

검증을 위해 시작한 GPU 컨테이너와 임시 DB는 종료했으며 결과 파일은 보존했다.

## 재현 및 산출물

검증 실행기: `evaluation/clustering_runtime_check.py`.
실행 단계: `prepare`, `seed-kb`, `intent`, `api`, `positive-match`.
API 검사는 실제 uvicorn 서버, 작업 저장소, 워커와 POSIX 자식 프로세스, 전체 분석 코드를 사용한다. 모델 함수나 추론 결과를 모의 처리하지 않는다. 완료 후 인증 실패, 중복 업로드, 최종 상태, 원본 로그 역매핑, FAQ/검색/답변 실행, 샘플 중복, 신규 단계의 예외 기록을 검사한다.

결과 루트: `evaluation/results/clustering-runtime-20261107-001/`.

- `fixture_manifest.json`, `input.csv`: 검증 입력과 원천 매핑.
- `runtime_config.json`: 패키지·모델 설정과 실제 실행 소스 SHA-256.
- `intent_results.initial.json`, `intent_results.json`: 보완 전후 실제 판정.
- `api_submit.json`, `api_status.json`, `api_checks.json`, `api_duplicate.json`: 실제 HTTP 결과.
- `api-jobs/analysis-1/logs/stdout.log`: 전체 모델 실행 로그.
- `api-jobs/analysis-1/performance.json`: 단계별 시간·메모리.
- `api-jobs/analysis-1/dataset/`: 질문 그룹, 군집, 후보, 매칭, 최종 답변 산출물.
- `summary.json`, `callback.json`, `stored_callback.json`, `new_stage_errors.json`: 종료 후 생성하는 최종 검증 결과.
- `positive-match/retrieval.json`, `decision.json`, `missing_id.json`, `answer.json`, `checks.json`: 실제 EXPAND 및 근거 답변 검사 결과.

회귀 로그: `tmp/clustering-linux-all-checks.log`.
외부 BE로 콜백을 전송하지 않는 standalone 모드다. 실제 운영 BE와 대시보드의 네트워크 연결까지 검증했다고 해석하면 안 된다. BE의 저장·조회 호환성은 앞선 Java/MariaDB 통합 테스트 23개로 별도 확인했다.
