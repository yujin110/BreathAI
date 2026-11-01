# BreathAI

Built with Llama. AI 서버의 현재 기본 LLM은 `meta-llama/Llama-3.1-8B-Instruct`입니다.
설정·배포·복구 및 검증 범위는 [Llama 전환 기록](docs/llama-migration-20261031.md)을 참고하세요.
승인 후 공식 8B 실행 결과와 남은 사실성 문제는 [실행·호환성 검증](docs/llama31-8b-execution-20261031.md)에 기록했습니다.
최신 답변 생성 방식과 참고용 20문항 결과는 [근거 외 생성 원인·수정 기록](docs/grounding-fix-reference-twenty-20261031.md)을 참고하세요.
해당 20문항에서 남았던 5건은 [추가 수정·재검증 기록](docs/remaining-five-fix-20261031.md)에 정리했습니다.

> AI 기반 미답변 분석을 통한 룰기반 챗봇 학습 데이터 자동화 현행화 시스템

챗봇 운영 과정에서 발생하는 대화 로그를 수집·분석하여 학습 데이터 구축과 FAQ 갱신을 자동화하는 시스템입니다.

<br>

## 팀원

팀장: 박서연 (BE)  
팀원: 김시환(AI), 모주현(AI), 오유진(FE)

<br>

## Git 협업 가이드

### 목차

- [1. Branch 전략](#1-branch-전략)
- [2. 작업 흐름](#2-작업-흐름)
- [3. 작업 타입](#3-작업-타입)
- [4. Commit 규칙](#4-commit-규칙)
- [5. Pull Request 규칙](#5-pull-request-규칙)
- [6. Issue 규칙](#6-issue-규칙)
- [7. 전체 작업 순서](#7-전체-작업-순서)

<br>

### 1. Branch 전략

#### 메인 브랜치

| 브랜치 | 역할 | 규칙 |
| --- | --- | --- |
| `main` | 최종 배포용. 항상 동작하는 상태를 유지합니다. | 직접 push 금지. `develop`에서만 PR로 병합합니다. |
| `develop` | 최신 개발 반영용. 기능 브랜치가 모이는 통합 브랜치입니다. | 직접 push 금지. 기능 브랜치에서 PR로 병합합니다. |

두 브랜치는 삭제하지 않고 계속 유지합니다.

#### 기능 브랜치

작업 하나당 브랜치 하나를 생성하고, 병합이 끝나면 삭제합니다.
개인 이름으로 브랜치를 만들어 계속 사용하지 않습니다.

**브랜치명 형식**

```
타입/영역/기능/역할
```

| 구성 | 설명 | 표기 |
| --- | --- | --- |
| 타입 | `feat`, `fix`, `refactor`, `docs`, `style`, `test`, `chore` | 소문자 |
| 영역 | 화면 또는 도메인 단위 (`community`, `home`, `auth`) | 소문자 |
| 기능 | 구체적인 작업 대상 (`review`, `feed`) | 소문자 |
| 역할 | `AI`, `BE`, `FE` | 대문자 |

**예시**

```
feat/community/review/BE
feat/community/review/FE
feat/home/feed/FE
fix/auth/token/BE
refactor/chat/pipeline/AI
```

#### 같은 기능을 여러 역할이 함께 개발하는 경우

브랜치를 공유하지 않고 역할별로 각자 생성합니다.
한쪽 작업이 끝나지 않아 다른 쪽이 병합하지 못하는 상황을 막기 위함입니다.

```
BE : develop → feat/community/review/BE → PR → develop
FE : develop → feat/community/review/FE → PR → develop
```

기능 단위의 묶음은 브랜치가 아니라 **Issue로 관리**합니다.
하나의 Issue에 역할별 PR을 함께 연결해 진행 상황을 추적합니다.
API가 필요한 작업은 브랜치를 나누기 전에 요청 및 응답 명세를 먼저 합의합니다.

<br>

### 2. 작업 흐름

1. 기능 Issue 생성 후 Issue 번호 발급
2. `develop` 브랜치를 최신 상태로 갱신
3. `develop`에서 기능 브랜치 생성
4. 기능 개발 및 Commit
5. 작업 완료 후 `develop` 브랜치로 Pull Request 생성
6. 코드 리뷰 후 Merge, 병합된 기능 브랜치 삭제
7. 배포 시점에 `develop` → `main` PR 생성

**명령어**

```bash
# 브랜치 생성
git checkout develop
git pull origin develop
git checkout -b feat/community/review/BE

# 작업 후 push
git push -u origin feat/community/review/BE

# 작업이 길어질 경우 중간에 develop 반영
git pull --rebase origin develop
```

<br>

### 3. 작업 타입

| 타입 | 설명 |
| --- | --- |
| `Feat` | 새로운 기능 추가 |
| `Fix` | 버그 수정 |
| `Refactor` | 동작 변화 없이 구조를 개선하는 작업 |
| `Docs` | README, 주석 등 문서 작성 및 수정 |
| `Style` | 코드 포맷, 네이밍, 세미콜론 등 기능과 무관한 스타일 변경 |
| `Test` | 테스트 코드 추가 및 수정 |
| `Chore` | 설정, 빌드, 패키지 등 기타 변경 작업 |

Issue, Commit, Pull Request, 브랜치명에 동일한 타입을 통일해서 사용합니다.
브랜치명에서만 소문자로 표기합니다. (`Feat` → `feat/...`)

<br>

### 4. Commit 규칙

```
타입: 간단한 설명

- 작업한 내용에 대한 구체적인 설명
- 필요한 경우 여러 줄로 상세하게 작성
```

**예시**

```
Feat: 커뮤니티 리뷰 작성 기능 추가

- 리뷰 작성 UI 구현
- 리뷰 등록 API 연결
- 작성 완료 후 리뷰 목록 갱신
```

<br>

### 5. Pull Request 규칙

기능 브랜치의 PR 대상은 `develop`입니다.
배포용 PR(`develop` → `main`)은 포함된 변경 사항을 목록으로 정리합니다.

```
타입: 간단한 설명

## 작업 내용
- 무엇을 변경했는지 간단히 작성

## 참고 사항
- 리뷰 시 유의해야 할 사항

## 관련 이슈
close #이슈번호
```

<br>

### 6. Issue 규칙

```
타입: 이슈 제목

## 이슈 개요
- 어떤 작업인지 간략히 설명

## 작업 항목
- [ ] 작업 1
- [ ] 작업 2
- [ ] 작업 3

## 참고 자료
- 관련 문서, 디자인, 링크 등
```

<br>

### 7. 전체 작업 순서

```
Issue 생성
→ develop 최신화
→ Branch 생성 (타입/영역/기능/역할)
→ 기능 개발
→ Commit
→ Pull Request (→ develop)
→ Code Review
→ develop Merge
→ Branch 삭제
→ (배포 시점) develop → main Merge
```

## FAQ 지식 데이터 설정

AI 검색기는 서울교통공사의 수제작 FAQ 두 자료와 운영자가 승인한
`gold_faq`를 병합하여 사용합니다. 배포 환경에서는 다음 환경변수로
원천 파일 경로를 지정합니다.

```text
TTOBAGI_GENERAL_FAQ_PATH=/data/일반상담.xlsx
TTOBAGI_COUNSELLING_FAQ_PATH=/data/counselling_info.csv
TTOBAGI_FAQ_MATCH_THRESHOLD=0.80
TTOBAGI_SIMILAR_QUESTION_LIMIT=5
```

환경변수가 없으면 각각 `dataset/일반상담.xlsx`와
`dataset/counselling_info.csv`를 찾습니다. 일반상담에만 존재하여
`counselling_info`와 연결되지 않는 FAQ의 `qType`은 `null`로 유지합니다.

활성 `gold_faq` 중 신규 FAQ(`source_seq_num IS NULL`) 또는 수정 이력이 있는
FAQ만 AI KB에 추가합니다. 미수정 복사본은 파일에서 읽으므로 원천 파일도
배포 환경에 유지해야 합니다. 수정된 FAQ는 같은 `source_seq_num`의 원본을 대체합니다.
일반상담의 0부터 시작하는 6~15열은 `유사질문1~10`으로, 키워드와 구분하여
`similar_questions`에 보관하고 검색 텍스트에 포함합니다.

Bronze 원본 테이블 생성·적재 및 외래키 변경은
[FAQ 원본 적재 안내](docs/faq-source-ingestion.md)를 참고하세요.
로컬 DB 반영·검증 결과와 후속 개발 항목은
[FAQ 개발 상태](docs/faq-development-status.md)에 정리합니다.

## 분석 파일 업로드

분석 업로드 API는 CSV와 XLSX를 지원합니다. XLSX는 첫 번째 시트를 CSV로
변환해 AI 서버에 전달하며 원본 파일명은 업로드 이력에 유지합니다.
파일 구조와 오류 응답은 [분석 업로드 안내](docs/analysis-upload-formats.md)를 참고하세요.


## AI 검색 메모리 및 군집화 설정

검색 KB는 HTML 태그와 base64 이미지를 제거한 텍스트를 사용합니다.
BGE-M3 입력 길이는 512 토큰으로 제한하며 기존 길이 설정이 없는 임베딩 캐시는 재생성합니다.
검색 임베딩과 reranker의 배치는 기본 4이며, VRAM이 부족하면
`TTOBAGI_RETRIEVAL_BATCH_SIZE=1`로 낮출 수 있습니다.

AI `POST /pipeline` multipart 요청은 `minClusterSize`(정수 2 이상, 기본 5)와
`minSamples`(정수 1 이상, 기본 2)를 지원합니다. 백엔드에서 이 값을 AI로 전달해야 적용됩니다.
HDBSCAN 클러스터 개수는 직접 지정할 수 없습니다.
자세한 변경 및 검증 범위는 [VRAM·군집화 안내](docs/vram-and-clustering-20261019.md)를 참고하세요.
