# Llama 모델 전환 기록 — 2026-10-31

Built with Llama.

승인 후 공식 8B의 실제 실행 결과, 발견한 결함·수정과 남은 품질 문제는
[실행·호환성 검증 기록](llama31-8b-execution-20261031.md)을 참고한다.

## 실행 변경

기준 저장소는 `BreathAI` develop, 원본 커밋은
`7a7e2ea635767b7c0954659e1f3418dd609e57c5`이다. 별도 작업 중인 다른 worktree,
과거 평가 결과, 다운로드된 Qwen 가중치는 변경·삭제하지 않았다.
원본 코드 ZIP은 `D:/projects/Capstone/server-backup/breathai-pre-llama-7a7e2ea.zip`이다.

기존 모델은 `Qwen/Qwen2.5-7B-Instruct`이며 외부 vLLM 호출이 아니라
Transformers 직접 로딩 방식이었다. 다음 실행 기본값을
`meta-llama/Llama-3.1-8B-Instruct`로 변경했다.

| 경로 | 변경 |
|---|---|
| services/faq_generator.py | FAQ 합성 모델·채팅 추론 공통화, 잘못된 필드 자료형은 REJECT 처리 |
| services/answer_regenerator.py | RAG 답변 모델·채팅 추론 공통화 |
| services/answer_evaluator.py | 품질 평가 Judge 모델·채팅 추론 공통화 |
| services/answer_refiner.py, oos_refresher.py | 재생성기의 공통 추론을 그대로 재사용 |
| services/run_pipeline.py | 기본 Llama 공유 로딩, 별도 Judge 설정 시 생성·평가 모델 분리 |
| services/llm_runtime.py | 모델 선택, 리비전, 양자화, chat template, EOS/EOT, 컨텍스트 예산 |

Embedding, BGE reranker, 클러스터링, KB 검색 및 DB 스키마는 유지한다.
Qwen 고정 모델 ID는 현재 실행 Python 코드에서 제거했다. 과거 기록의 Qwen은
실측 이력과 복구를 위해 보존한다. 기존 API 응답 키는 변경하지 않는다.

모델에 내장된 chat template을 적용하고 토큰을 중복 인코딩하지 않는다.
모델 generation config의 EOS와 Llama EOT를 모두 보존한다. 입력은 실제 임베딩
장치로 이동하며 attention mask를 전달한다. 기본 입력+출력 한도는 8,192 토큰이다.
예산 초과 시 근거/지침을 임의로 자르는 대신 명확한 오류를 낸다.
사용자·근거 데이터에는 모델 remote code 실행을 허용하지 않는다.

## 설정 및 배포

`deployment.env.example`을 참고해 `.env.ai`에 설정한다. 환경변수는 **프로세스 시작 전**
설정하고 변경 후 worker를 재시작한다. 모델 ID, 로컬 경로 모두 지원한다.

```dotenv
TTOBAGI_LLM_MODEL=meta-llama/Llama-3.1-8B-Instruct
TTOBAGI_JUDGE_MODEL=meta-llama/Llama-3.1-8B-Instruct
TTOBAGI_LLM_REVISION=
TTOBAGI_JUDGE_REVISION=
TTOBAGI_LLM_QUANTIZATION=none
TTOBAGI_LLM_CPU_OFFLOAD=false
TTOBAGI_LLM_CONTEXT_TOKENS=8192
```

재현 가능한 배포에는 승인된 모델의 commit hash를 두 REVISION에 기록한다.
HF_TOKEN은 모델 접근 승인된 읽기 토큰을 비밀 설정으로 주입한다. 로그나 Git에 저장하지 않는다.
Llama 파일 다운로드는 다음처럼 수행할 수 있다.

```bash
python -c "from huggingface_hub import snapshot_download; snapshot_download('meta-llama/Llama-3.1-8B-Instruct', local_dir='/data/models/llama-3.1-8b')"
python -m pip install -r requirements-runtime.txt
python -m pip check
python -m evaluation.llm_migration_check --cases evaluation/llama_smoke_cases.json --run-dir evaluation/results/llama-8b-smoke-001
```

위 명령은 Linux GPU 서버 또는 적절한 로컬 Python 환경에서 실행한다.
Vast는 선택 사항이다. 현재 사용자는 비용 때문에 서버를 중지했으므로 서버를
새로 개설하거나 재시작하지 않는다. 기존 주소는 연결 시간 초과였고 원격 변경은 없다.

BF16 8B 가중치만 대략 16GB이며 KV cache, 활성화, CE, 임베딩 및 CUDA 메모리가
추가된다. 이는 산술 추정이며 현재 GPU 실측 요구량이 아니다. 6GB 로컬 GPU에서는
`TTOBAGI_LLM_QUANTIZATION=4bit`를 검토하되 8B 동작을 보장하지 않는다.
4bit는 bitsandbytes/NF4를 사용하며 CUDA를 요구한다. 별도 Judge 동시 로딩은 메모리를
추가 사용한다. 정식 비교는 동일한 독립 Judge를 별도 순차 실행하는 편이 적절하다.

2026-10-31 승인 후 공식 8B 가중치 4개를 다운로드했고 모두 Hugging Face LFS SHA-256과
일치함을 확인했다. [모델 파일 명세](llama31-8b-model-manifest.json)를 참고한다.
로컬 RTX 2060에서는 4bit 자동 CPU 배치가 quantization meta tensor 오류를 발생시켜,
`TTOBAGI_LLM_DEVICE_MAP`으로 배치를 명시해 로딩 및 짧은 추론을 확인했다.

```dotenv
TTOBAGI_LLM_QUANTIZATION=4bit
TTOBAGI_LLM_CPU_OFFLOAD=true
TTOBAGI_LLM_DEVICE_MAP={"model.embed_tokens":"cpu","model.layers":0,"model.norm":0,"lm_head":"cpu"}
TTOBAGI_CE_DEVICE=cpu
```

위 레이아웃은 Llama 3.1 구조용 선택 설정이며 일반 운영 서버의 기본값이 아니다.
명시적 장치 배치는 자동 배치 예산을 대신하므로, 활성화·캐시를 포함한 실제 여유 메모리는
실행으로 확인해야 한다. 오프로딩된 임베딩은 임시 meta weight 장치가 아닌 Accelerate hook의
실제 실행 장치로 입력을 보낸다. GPU의 native BF16 지원이 없으면 FP16으로 계산한다.

Linux Docker host에서는 기존 `compose.ai.yaml`을 그대로 사용한다.

```bash
docker compose --env-file .env.ai -f compose.ai.yaml build worker
docker compose --env-file .env.ai -f compose.ai.yaml up -d api worker
```

기존 문서의 Vast 실행 방식이 있으면 해당 방식으로 requirements와 환경변수를 적용한다.
Vast 컨테이너 안에서 중첩 Docker를 전제하지 않는다. 기존 입력 데이터·FAQ 소스·DB 및
BE callback 설정은 별도로 유지한다. 실제 운영 전 격리 작업으로 분석 성공, FAQ 저장/조회,
승인 반영, 실패 callback을 확인한다. 이번 작업에서 운영 DB 승인이나 새 분석 요청은 보내지 않았다.

## 비교 및 검증 도구

`evaluation/llm_migration_check.py`는 고정 messages JSON을 읽어 새 결과 폴더에만
응답·실패·시드·프롬프트 해시·chat template 해시·패키지·로딩/요청 시간·CUDA peak·
표본 RSS·GPU 사용률/메모리·로컬 모델 크기를 기록한다. 이전 답변/점수를 재사용하지 않는다.
실행마다 새 폴더가 필요하며 DB에는 접근하지 않는다.

같은 `--cases`, seed, 양자화, 출력 길이로 모델만 바꿔 별도 프로세스에서 실행한다.
기본 예제는 **3개 연결 확인용 입력**이다. 정식 20문항×5조건 평가가 아니다.
측정되는 CUDA peak는 PyTorch 할당량, GPU 샘플은 전체 장치, RSS는 0.1초 간격 프로세스
표본 최대값이다. 이 값들을 VRAM/RAM의 완전한 시스템 peak로 해석하지 않는다.
프로세스 cold load를 포함한 처리량과 요청 지연 시간을 구분한다.

과거 20문항 자료는 별도 평가 worktree의
`evaluation/results/develop-20-20261020/benchmark.json`, `retrieval.json`에 존재한다.
기존 5조건은 Llama baseline / Qwen baseline / cluster_synth / RAG / conditional Refine이다.
사용자 검토 결과 이 실험은 실질적 성능 평가의 근거로 사용하지 않는다.
기존 20문항을 정식 평가셋으로 그대로 재사용하지 않으며, 이후 대표성·독립 정답·근거와
채점 기준을 갖춘 평가 설계를 별도로 확정해야 한다.
새 평가에서 고정 KB·질문·검색 근거를 사용하고 **답변, Judge 평가, Refine gate는 새로 생성**한다.
모델만 바꾸어 두 파이프라인을 비교하고 동일한 독립 Judge로 채점한다.
승인율 75%는 담당자의 실제 승인 기록으로 계산해야 한다. 자동 점수나 REJECT 제외 비율로
대체하지 않는다. 클러스터링 노이즈 10% 목표는 전체 파이프라인 회귀 항목으로 확인한다.

실제 결과와 미완료 항목은 [로컬 검증 보고서](llama-migration-validation-20261031.md)에 기록한다.

## 라이선스·운영 검토

공식 [모델 카드와 Llama 3.1 Community License](https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct)를
검토했다. 한국어는 공식 지원 8개 언어에 포함되지 않는다. 따라서 한국어 도메인 평가와
사실성 검증을 완료하기 전 최종 운영 모델 채택으로 간주하지 않는다.
모델 접근에는 Hugging Face 계정 정보 공유/접근 승인이 필요하다.

서비스 제공·배포 시 Built with Llama 표시, 해당 라이선스 제공, 재배포 Notice 및
Acceptable Use Policy 준수 조건을 확인해야 한다. 대규모 서비스의 추가 상업 조건도 존재한다.
이 문서는 구현 검토 기록이며 서울교통공사·담당 기관의 보안/라이선스 승인을 대신하지 않는다.
실제 서비스 UI 고지와 모델 재배포 패키지의 라이선스 동봉은 운영 배포 시 확인한다.

## 복구

Qwen 가중치와 과거 평가 결과를 삭제하지 않았다. 기본 추론기는 모델별 chat template과
종료 토큰을 사용하므로 긴급 복구는 다음 환경변수로 가능하다.

```dotenv
TTOBAGI_LLM_MODEL=Qwen/Qwen2.5-7B-Instruct
TTOBAGI_JUDGE_MODEL=Qwen/Qwen2.5-7B-Instruct
TTOBAGI_LLM_REVISION=
TTOBAGI_JUDGE_REVISION=
TTOBAGI_LLM_QUANTIZATION=none
```

기존 리비전/메모리 예산·경로 설정이 있다면 함께 복원하고 worker를 재시작한다.
정확한 이전 코드 복원은 원본 커밋 또는 보관 ZIP을 별도 폴더에서 사용한다.
진행 중인 작업을 강제 종료하거나 사용자 변경을 덮는 `git reset --hard`는 사용하지 않는다.
