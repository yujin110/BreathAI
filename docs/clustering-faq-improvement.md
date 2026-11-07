# 군집화 및 FAQ 후보 개선 구현

기준: `feat/analysis/target-selection/AI`, `b79cac9`.
요청 명세의 CL-01/02, FQ-01/02/03을 기존 전체 실행 경로에 연결했다.
운영 배포나 실제 DB 변경은 수행하지 않는다. 배포 시 Flyway V17이 신규 JSON 컬럼을 추가한다.

## 처리 및 관계 보존

- 전처리에서 Unicode NFC를 적용한다. 정규화 문자열별 고유 질문을 임베딩·군집화하고 결과를 모든 원본 로그에 역매핑한다.
- 고유 질문이 군집화 최소 크기에 미달하면 모델/UMAP을 실행하지 않고 모든 입력 로그를 노이즈로 보존한다. 노이즈를 FAQ 하나로 묶지 않는다.
- 군집 중심의 원본 임베딩 cosine 0.85 이상인 쌍만 답변 의도 검증 대상으로 삼는다. 모든 원래 구성원 쌍의 기준을 만족하는 complete-link 방식과 병합 묶음 전체의 재검증을 적용한다. 이름과 TF-IDF 키워드는 병합 근거가 아니다.
- 의도 검증기는 현재 설정된 채팅 모델을 지연 로딩한다. 반대 방향 요청, 다른 절차, 세부 조건을 구분하도록 구성했다. JSON 오류, 모델 로딩 실패, 문맥 한도 초과는 `unknown`으로 기록하여 원본을 유지한다. 확실한 `same`만 병합한다.
- FAQ 프롬프트는 중복을 제거하고 중심 대표성/MMR로 최대 12개 질문을 선택한다. 이미 선택한 질문과 cosine 0.97 이상인 표현은 제외한다.
- 모든 후보 생성 후 표준 질문 임베딩과 원천 질문의 답변 의도를 검증해 중복 후보를 통합한다. 임베딩은 CPU에서 계산하여 생성 모델과의 GPU 메모리 중복을 줄인다.
- 기존 `clusterLabel`은 대표 군집으로 유지한다. `finalClusterIds`, `logIds`, `sourceCandidateIds`에 전체 관계를 보존하고 출현 수는 고유 로그 ID 수로 계산한다.
- 콜백은 후보의 로그 집합이 참조 군집 전체의 로그 집합과 일치하는지 검사한다. 분석별 `analysisProvenance` JSON을 DB에 저장하고 분석 결과·후보 목록 API로 반환한다. JSON의 군집 번호와 업로드 로그 번호는 해당 analysis ID 안에서 해석한다.
- 화면 질문 펼치기는 모든 참조 군집을 표시한다. 군집 이름에는 번호를 병기한다. 기존 완료 콜백 재전송 방지와 FAQ 승인 정책을 유지한다.

## 운영 FAQ 판정

활성 gold FAQ의 수정하지 않은 원천 복사본도 검색에 포함한다. 파일의 운영 ID 주장은 사용하지 않고 DB에서 읽은 레코드의 ID를 사용한다. 전체 검색 Top-20에 더해 유효 운영 FAQ에서 별도 Top-20을 검색하고 합친 후보를 재정렬한다.

BGE reranker는 명시적 sigmoid를 사용한다. 점수는 보정된 정답 확률이 아니다. 전체 검색 Top-5는 답변 근거용으로 유지하고 운영 FAQ 판정은 재정렬된 전체 후보를 사용한다.

- 유효 ID, 높은 점수, 동일 답변 의도: EXPAND.
- 기본 0.80의 ±0.02 경계 구간 또는 불명확한 의도: NEW 상태로 검토 표시.
- 유사 FAQ의 ID 누락: `ID_LINK_ERROR` 근거 기록. 자동 확장하지 않는다.
- 상이한 의도 또는 관련 운영 FAQ 없음: NEW.
- 정제 후 질문이 바뀌면 기존 확장 매칭을 해제하고 다시 검토하도록 표시한다.

`matchedFaqs`에는 확정된 대상만 넣는다. 별도 `matchEvidence`에 검색 점수, reranker 점수, ID, 원천, 상태, 사유를 남긴다. UI는 검토 필요 배지를 지원하며 검색 점수를 확률로 표시하지 않는다.

## 추가 산출물

| 파일 | 내용 |
| --- | --- |
| question_groups.json | 그룹 ID, 정규화 질문, 빈도, 모든 로그 ID, 초기/최종 군집, 병합 판정 |
| unique_embeddings.npy | 고유 질문 순서의 임베딩 |
| clusters.csv | 기존 원본 로그별 행과 추가 그룹 ID·초기 군집 ID |
| cluster_summary.csv | 원본 로그 수 size, 고유 질문 수 uniqueSize |
| clustering_quality.json | 두 기준 노이즈율, 입력·고유 질문 수, 동일 질문 정합성 |
| faq_merge_history.json | 원천 후보 순서, 판정, 병합 이력, 후보-군집-로그 관계 |
| faq_candidates*.json | 전체 관계와 운영 FAQ 매칭 근거 |

기존 embeddings.npy는 원본 로그별 행 순서를 유지하여 기존 소비자와 호환된다. 병합 전 개별 FAQ 응답은 faq_candidates.jsonl 체크포인트에 남는다. 신규 단계가 실패하면 병합하지 않고 오류 근거를 남긴다.

## 검증 및 실측

후속 실제 모델·HTTP 실행 검증과 추가 수정은 [실행 검증 보고서](clustering-runtime-validation-20261107.md)를 참고한다.

2026-11-07 로컬 검증 결과:

- Python 전체 회귀 168개 실행: 159개 통과, 기존 플랫폼/POSIX 또는 선택 실행 조건에 따른 9개 건너뜀. 이후 평가 도구의 로그 ID 기반 매칭 보완도 해당 단위 테스트로 통과했다.
- 프론트엔드 Vite 프로덕션 빌드 성공.
- Java 17 + 격리 MariaDB에서 백엔드 23개 통과: AnalyzeResultResponseTest 1개, AnalysisScopeIntegrationTest 9개, ExpandTargetIntegrationTest 13개. V17 실행과 추가 JSON 근거의 DB 저장/두 조회 API 반환 포함.
- Git diff 공백 오류 없음. 운영 DB·배포 환경은 변경하지 않았다.

회귀 테스트는 동일 문장 4/100건, Unicode 정규화, 같은 의도/반대 의도, 연쇄 병합 차단, 전체 묶음 재검증, MMR, FAQ 중복 통합, ID/빈도 보존, 검색 1위가 아닌 운영 FAQ, ID 누락, 경계 점수, 모델 오류, IMPROVEMENT 선택을 다룬다. 19,000건 합성 입력 검사는 고유 질문 190개와 원본 ID/빈도 역매핑을 확인한다. 의도 검증의 단위 테스트는 통제된 판정 함수를 사용하며 실제 모델 정확도를 입증하지 않는다.

전후 실행 폴더를 보존한 뒤 다음 명령으로 비교한다:

```powershell
python -m evaluation.clustering_quality --before OLD/dataset --after NEW/dataset --labels labels.json --output comparison.json
```

labels.json 예시:

```json
{
  "pairs": [{"leftLogId": 1, "rightLogId": 2, "sameIntent": true}],
  "matches": [{"logId": 1, "candidateType": "EXPAND"}]
}
```

비교 도구는 두 실행의 선택 입력 ID와 정규화 질문이 같은지 확인한다. 군집 수, 동일 질문 분리, 두 기준 노이즈율, 로그 보존율, 문자열 FAQ 중복, 샘플 중복, 라벨 기반 잘못된 병합률/병합 재현율/확장 정확도를 출력한다. 평가 가능한 라벨 수와 제공 라벨 수를 함께 기록한다. 실행 폴더의 performance.json이 있으면 시간·RAM·GPU 메모리도 포함한다. `--accepted N --reviewed M`으로 채용률을 기록한다. 미측정은 null이며 의미 중복률을 문자열 중복률로 대체하지 않는다.

현재 로컬 dataset에는 비교에 필요한 preprocessed_full.csv가 없다. 명세에서 언급한 실제 약 19,000행 실행의 전후 산출물과 정답 라벨도 이 작업에서 확보되지 않았다. 따라서 실제 모델의 과도한 병합률 0%, 병합 재현율/확장 정확도 95%, 노이즈율 10%, FAQ 채용률 75% 달성을 주장하지 않는다. 같은 모델·환경·선택 대상에서 실행한 전후 산출물과 사람의 정답 라벨/검토 결과가 있어야 해당 수치를 측정할 수 있다. 0.85와 0.80은 초기 비교값이며 실측 후 보정해야 한다.
