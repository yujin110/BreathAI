# Llama 3.1 8B 실행·호환성 검증 — 2026-10-31

이후 자유 답변 생성 경로를 근거 선택·원문 조립 방식으로 수정했다.
최신 동작과 참고용 20문항은 [원인·수정 및 재검증 기록](grounding-fix-reference-twenty-20261031.md)을 우선 참고한다.

## 판정

공식 모델 다운로드, 로컬 로딩, FAQ JSON, RAG 답변 재생성, Refine, 런타임 Judge의
추론 경로를 실제 Llama 3.1 8B로 실행했다. 코드 전환과 실행 호환성은 확인했지만,
**사실성 문제와 Judge의 과대 평가가 남아 운영 적합성·최종 채택은 미확정**이다.
기존 20문항 실험은 성능 입증 자료에서 제외한다. 이번 합성 입력 검사도 정식 성능 평가가 아니다.

## 모델과 환경

- 모델: `meta-llama/Llama-3.1-8B-Instruct`
- 리비전: `0e9e39f249a16976918f6564b8830bc894c89659`
- 공식 가중치 4개 모두 Hugging Face LFS SHA-256과 일치. [파일 명세](llama31-8b-model-manifest.json)
- 로컬 GPU: RTX 2060 6GB, 시스템 RAM 약 16GB.
- 4bit NF4, double quantization, native BF16 미지원 GPU에서 FP16 계산.
- 임베딩·출력층 CPU 오프로딩, 디코더·정규화층 GPU 명시 배치.
- 입력+출력 설정 한도 4,096 토큰. 이 값은 설정이며 최대 길이까지 안전한 메모리 검증을 한 것은 아니다.
- torch 2.9.1+cu126 / transformers 4.57.3 / accelerate 1.15.0 / bitsandbytes 0.48.2.
  운영 requirements pin과 일부 다르므로 배포 이미지 검증은 별도다.
- 짧은 연결 검사 중 CUDA 할당 peak 약 4.37GiB,
  프로세스 표본 RSS peak 약 6.65GiB.
  작은 입력·CPU 오프로딩 환경의 참고 기록이며 운영 자원 요구량이나 처리량 평가가 아니다.

## 발견한 문제와 수정

| 문제 | 수정·검증 |
|---|---|
| 4bit 자동 배치에서 quantization meta tensor 오류 | 선택적인 명시 device map 지원. CPU에는 임베딩·출력층, GPU에는 디코더를 두어 실제 로딩 확인 |
| 오프로딩 임베딩의 weight 장치가 meta | Accelerate hook의 실행 장치로 입력 이동. 회귀 테스트 추가 |
| Qwen용 FAQ 프롬프트의 JSON 예시가 유효한 JSON이 아님 | enum을 값에 섞던 예시를 올바른 JSON으로 변경하고 system 출력 지침 강화 |
| Llama가 마크다운 출력, 파서가 내부 동의어 객체를 FAQ로 인식 | tag를 가진 FAQ 객체만 디코딩, 필수 스키마 검사 및 1회 재시도, 최종 실패 시 REJECT |
| 근거 밖의 시간·절차 생성 | RAG 시스템 지침과 Refine의 이전 답변 비신뢰 지침 강화. 사실성 문제가 완전히 해결되지는 않음 |
| Judge가 검색 근거 없이 정확도 5점 부여 | 실제 사용한 검색 근거를 Judge에 전달하고 근거 기반 채점 지침 강화. 같은 모델의 높은 점수를 정확성 증거로 사용하지 않음 |
| 작은 GPU에서 CE와 생성 모델의 메모리 경쟁 | Cross-Encoder를 변경하지 않고 CPU 실행을 선택할 수 있게 함. 전체 동시 로딩 E2E 검증은 미실행 |

## 실제 출력 확인

1. 짧은 한국어/JSON/수정 입력 3개는 모두 추론 완료. 그러나 엘리베이터 예시에 근거에 없는
   대체 서비스가 추가됐다. 추론 성공과 답변 정확성을 구분한다.
2. 기존 실제 FAQ 프롬프트는 마크다운을 생성했고, 내부 동의어 객체가 잘못 파싱됐다.
   수정 후 같은 합성 클러스터로 FAQ JSON을 첫 시도에 생성하고 스키마 검사를 통과했다.
   동의어 중복·과도하게 일반적인 표현과 미검증 안내는 별도 품질 검토 대상이다.
3. 기존 Refine은 근거에 없는 `1시간 이내` 조건을 만들었다. 지침 강화 후 해당 조건은
   이번 출력에서 사라졌지만, 물건 위치를 파악할 수 있다는 효과·절차 설명이 여전히 추가됐다.
4. 근거를 받은 Judge도 RAG 답변에 정확도 5점을 줬다. 이 점수는 담당자 정성 평가나
   독립 Judge 평가를 대체하지 않으며 사실성을 보장하지 않는다.

입력은 합성 근거 한 건이며 실제 검색기·KB/DB 연결·클러스터링은 실행하지 않았다.
Refine은 잘못된 이전 답변과 명시적 검수 의견을 주어 강제로 실행했다.
조건부 gate의 발생률이나 개선율을 측정한 것이 아니다. RAG·Refine은 실제 서비스 함수를 사용했다.
FAQ 수정 재검증과 답변 지침 재검증은 별도 실행이므로 한 번의 전체 E2E 통과로 표현하지 않는다.

## 기록

- 최초 로딩 실패: `evaluation/results/llama-31-8b-local-chat-001/manifest.json`
- 짧은 추론 3건: [응답](../evaluation/results/llama-31-8b-local-chat-002/answers.json),
  [환경·자원](../evaluation/results/llama-31-8b-local-chat-002/manifest.json)
- 기존 프롬프트의 문제 재현: [출력](../evaluation/results/llama-31-8b-pipeline-001/results.json)
- FAQ 수정 재검증: [출력](../evaluation/results/llama-31-8b-faq-fixed-001/results.json),
  [스키마 결과](../evaluation/results/llama-31-8b-faq-fixed-001/summary.json)
- 답변 지침·근거 Judge 재검증: [출력](../evaluation/results/llama-31-8b-answers-fixed-001/results.json),
  [연결 결과](../evaluation/results/llama-31-8b-answers-fixed-001/summary.json)

원시 결과는 기존 Git 제외 정책을 유지한다. 로컬 결과 파일을 다른 PC에서 볼 때는 별도 전달이 필요하다.
합성 입력 연결 결과로 관련성·사실성·승인율·성능 우열을 산출하지 않았다.

## 회귀 검증 및 남은 일

최종 unittest 89개 실행: 88개 통과, 1개 건너뜀. 로그는 `tmp/llama-8b-final-tests.log`다.
FAQ 파싱/재시도/필드 자료형, EOT, 컨텍스트 제한, 장치 이동, Judge 근거 전달과 기존 API·작업 상태·
검색 근거 보존·DB 모의 회귀를 포함한다. 테스트 통과가 실제 DB·운영 배포나 품질 승인이라는 뜻은 아니다.

- M-10: 승인된 공식 Instruct 모델 다운로드·파일 검증 완료.
- M-12: 로컬 4bit/CPU 오프로딩 로딩 및 한국어 생성 확인.
- M-13~18: 공통 호출·토크나이저·종료·장치·설정·파싱·복구 구현 및 회귀 확인.
- M-19~24: 실제 프롬프트/생성/수정 호출을 합성 근거로 실행. 사실성 검증은 미충족.
- M-25: FAQ 출력 스키마를 실제 모델과 회귀 테스트로 확인.
- M-11·26~29: Vast 실행 환경 및 실제 BE/DB 저장·조회·관리자 승인 E2E 미실행.
- 운영 적합성·기관 라이선스/보안 승인·담당자 승인율 75%·새 평가 설계는 미완료.

운영 채택 전에는 대표성 있는 독립 질문·검증된 근거·명확한 채점 기준으로 평가를 새로 설계해야 한다.
한국어 사실성과 자기 평가의 신뢰성이 남은 핵심 문제다. 성능 판단에 기존 20문항을 재사용하지 않는다.
Vast 서버는 중지 상태를 유지했고 운영 DB·백엔드는 변경하지 않았다.
[설정·복구 절차](llama-migration-20261031.md)를 통해 원본 커밋/백업과 이전 모델을 복원할 수 있다.
