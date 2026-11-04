# 분석 중도 취소

## 외부 계약

FE는 기존 PATCH /api/v1/dashboard/analyze/{analysisId}/cancel을 호출한다. BE는 요청자를 검증하고 취소 의도를 저장한 뒤 POST /jobs/{analysisId}/cancel로 AI 중단을 요청한다. 기존 AI API Bearer 토큰을 사용한다.

AI 응답은 200 취소 및 정리 완료, 404 미등록 작업, 409 종료된 작업이다. CANCELED 재요청도 409다. 내부 복구 중에는 GET /jobs/{analysisId}로 CANCELED인지 확인하고 BE 처리를 마무리한다. COMPLETED 또는 FAILED를 취소 성공으로 해석하지 않는다. 완료는 콜백 전달 여부와 별개다.

종료나 정리를 완료하지 못하면 AI는 503을 반환하고 CANCELLING을 유지한다. BE도 확인 실패에 503을 반환하며 취소 의도를 유지한다. UI는 성공으로 표시하지 않으며 기존 SSE 또는 폴링으로 최종 상태를 확인한다.

## 실행과 정리

- 운영 worker는 POSIX 환경에서 분석별 별도 세션으로 services.job_process를 실행한다.
- 프로세스 PID와 생성 시각을 저장하고 자식은 이 등록을 확인한 뒤 파일 준비와 추론을 시작한다. 등록 전에 worker가 종료되면 분석을 시작하지 않는다.
- 취소는 세션 내 GPU 자식 프로세스까지 종료한다. 세션 리더가 먼저 종료된 경우에도 같은 세션의 남은 프로세스를 확인한다.
- 잠금은 삭제하지 않는 control/<analysisId>.lock에 둔다. 취소, 프로세스 등록, 완료 확정, 콜백 전송이 동일 잠금을 사용한다.
- 콜백 HTTP 요청은 잠금 안에서 시작한다. 취소가 먼저 상태를 전환하면 신규 전송이 차단된다. 전송을 이미 시작한 요청은 회수하지 못하므로 BE 종료 상태 보호도 유지한다.
- 프로세스 종료 확인 후 uploads 안의 해당 입력 파일과 analysis-<analysisId> 폴더를 삭제한다. 경로와 심볼릭 링크를 검사한다.
- jobs.sqlite3, 취소 시각, 정리 오류, BE 원본 파일 및 분석 이력은 보존한다.
- worker는 루프 시작 시 CANCELLING 작업을 복구한다. 정리가 끝나지 않은 작업이 있으면 새 GPU 작업을 선점하지 않는다.
- 별도 세션으로 실행한 분석도 컨테이너 서비스 종료 시 남지 않도록 관리한다.

## 제출 중 취소

BE의 ai_submission_state는 PENDING, SENDING, ACCEPTED, REJECTED, UNKNOWN을 구분한다. 취소 의도와 제출 시작은 같은 DB 행 잠금으로 직렬화하며 네트워크 호출 중에는 잠금을 유지하지 않는다.

- 아직 제출하지 않은 PENDING은 취소 의도를 저장하여 이후 제출을 차단한다.
- 명확히 거절된 REJECTED도 제출할 AI 작업이 없으므로 BE 취소를 완료할 수 있다.
- SENDING 또는 UNKNOWN에서 AI가 404를 반환해도 성공으로 처리하지 않는다. 접수가 늦게 완료될 수 있으므로 취소 의도를 보존하고 재시도한다.
- 통신 장애로 제출 여부가 불명확하고 AI에도 계속 작업이 없다면 자동으로 미접수를 단정하지 않는다. 이 경우 503 상태 확인 실패가 지속될 수 있으며 제출 기록을 확인해야 한다.
- AI 완료 또는 실패가 먼저 확정된 경우에는 해당 종료 콜백을 보존하고 취소에 409를 반환한다. 취소 진행 중인 진행 콜백과 취소 완료 이후의 모든 콜백은 상태를 덮어쓰지 못한다.

BE는 영속화한 취소 의도를 기본 10초 간격으로 재시도하므로 재시작 후에도 복구할 수 있다.

## 배포와 설정

- AI API와 worker가 같은 로컬 JOB_ROOT 및 PID namespace를 사용해야 한다. 기존 단일 GPU worker 구성을 유지한다. compose.ai.yaml의 API는 pid: service:worker로 worker의 PID namespace에 참여한다. 한 컨테이너에서 all 모드로 실행하는 구성도 지원한다.
- 서로 다른 PID namespace의 프로세스 번호를 해석하지 않는다. 설정 불일치 시 503을 반환하고 입력을 보존한다. worker 컨테이너를 교체하면 API도 함께 재시작해야 한다.
- 같은 PID namespace에서 worker가 재시작되면 CANCELLING 정리를 재개한다. 컨테이너 교체로 namespace가 달라진 이전 실행 기록이나 변경 전 버전에서 실행 중이던 작업은 프로세스 종료를 운영자가 확인한 후 조정해야 한다. 이 경우 자동으로 정리 완료를 단정하지 않는다.
- 파일 잠금 및 Linux 세션 검사를 사용하므로 GPU worker는 Linux에서 실행한다.
- AI 이미지에는 requirements-api.txt의 psutil이 필요하다. API와 worker 이미지를 함께 갱신한다.
- BE 배포 시 V14__add_ai_cancellation_intent.sql 마이그레이션을 적용한다. 기존 분석의 제출 상태는 안전하게 UNKNOWN으로 둔다.
- TTOBAGI_CANCEL_GRACE는 기본 10초, TTOBAGI_CANCEL_TIMEOUT은 기본 30초다.
- BE의 AI 취소 호출은 35초, 상태 조회는 5초 제한을 사용한다. 프록시와 클라이언트는 이 대기 시간을 허용해야 한다.
- analysis.cancel.retry-delay-ms로 BE 복구 간격을 조정할 수 있다. 기본값은 10000이다.

## 검증 범위

tests/test_job_cancellation.py는 대기 취소, 인증과 응답, 콜백 전송 경쟁, 정리 실패 복구, 경로 보호, PID 재사용 방어, 실제 Linux 자식 프로세스 종료와 고아 프로세스 정리를 검증한다. GPU 모델을 실행하지 않고 일반 자식 프로세스로 종료 경계를 시험한다.

BE 테스트는 요청자 권한, 실제 AI 호출 경로와 인증 헤더, 미등록 응답 및 통신 실패, 취소 의도 복구와 완료 경쟁을 검증한다. FE는 CANCELED를 SSE, 폴링, 이력에서 종료 상태로 처리하며 cancelable 응답에 따라 버튼을 표시한다.

### 실행 결과

2026-11-05 작업 기준으로 다음 검증을 완료했다.

| 검증 | 결과 |
| --- | --- |
| Linux AI 취소, API, 콜백, 작업 실행, 컨테이너 종료 테스트 | 39개 통과, 실제 HTTP multipart 검사 포함 |
| BE 취소 서비스 및 MariaDB 통합, WebClient, SSE 테스트 | 19개 통과 |
| FE 브라우저 자동 검증 | 취소 이력 종료 처리, cancelable 권한, 503 실패 유지, 성공 후 화면 종료의 4개 시나리오 통과 |
| FE 프로덕션 빌드 및 변경 파일 ESLint | 통과 |
| Compose 설정 검사 | 통과 |
| Git 변경 사항 공백 검사 | 통과 |

BE 테스트의 Mockito는 Docker에서 동적 JVM 연결을 요구하지 않도록 테스트 시작 시 에이전트를 로드한다. 테스트 DB는 별도 MariaDB 컨테이너를 사용하며 운영 DB에 접근하지 않았다.

실제 GPU 모델을 로드한 상태에서의 취소와 운영 배포는 수행하지 않았다. 프로세스 종료 검증은 종료 신호를 무시하는 일반 자식 프로세스 및 고아 프로세스로 수행했다. 분리된 API와 GPU 컨테이너의 운영 실행은 갱신한 Compose PID 공유 설정을 적용해야 하며, 기존 실행 작업과 이전 PID namespace의 기록은 배포 전에 확인해야 한다.
