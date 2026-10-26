# BE 배포 절차 (NCP)

서버 1대에 BE와 MariaDB를 도커로 함께 띄우고, nginx가 HTTPS(443)를 받아 BE로 넘긴다.
2026-10-18 실제 배포 과정을 정리한 것이다.

```
FE(GitHub Pages) ──HTTPS──▶ nginx(443) ──▶ BE 컨테이너(127.0.0.1:8080) ──▶ MariaDB 컨테이너(127.0.0.1:3306)
AI 서버(Vast) ◀── SSH 터널(서버가 연다) ──▶ BE·DB
```

## 1. 서버

| 항목 | 값 |
|---|---|
| 서버 | NCP VPC, Standard s2-g3a (vCPU 2, 메모리 8GB), 시간 요금제 |
| OS | Ubuntu 24.04 (콘솔에 22.04 이미지가 없음) |
| 디스크 | CB2 50GB (기본 디스크 하나) |
| 네트워크 | Public 서브넷, 공인 IP 1개 |

ACG 규칙

| 방향 | 프로토콜 | 대상 | 포트 | 용도 |
|---|---|---|---|---|
| Inbound | TCP | 관리자 PC IP/32 | 22 | SSH |
| Inbound | TCP | 0.0.0.0/0 | 80 | 인증서 발급 확인, HTTPS로 이동 |
| Inbound | TCP | 0.0.0.0/0 | 443 | HTTPS |
| Outbound | TCP·UDP | 0.0.0.0/0 | 1-65535 | 패키지 설치, 메일, 인증서, 터널 |
| Outbound | ICMP | 0.0.0.0/0 | - | |

직접 만든 ACG에는 기본 규칙이 없으므로 Outbound도 직접 넣는다. 3306, 8080은 열지 않는다.

## 2. 기본 설치

```bash
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a
apt-get update && apt-get -y upgrade        # 끝나고 /var/run/reboot-required 가 있으면 reboot

# Docker 공식 저장소
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
  docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

## 3. 코드와 환경변수

```bash
git clone --branch develop https://github.com/seo-yn/BreathAI.git /opt/breathai
cd /opt/breathai/ttobagi-server
```

`.env`는 `.env.example`을 기준으로 만든다. Windows에서 만든 파일을 복사했다면 BOM과 CRLF를 먼저 지운다.

```bash
sed -i '1s/^\xEF\xBB\xBF//; s/\r$//' .env
chmod 600 .env
```

서버에서 따로 정하는 값

| 키 | 값 |
|---|---|
| `TTOBAGI_DB_URL` | `jdbc:mariadb://mariadb:3306/ttobagi_db` (compose가 덮어쓰지만 맞춰 둔다) |
| `TTOBAGI_DB_PASSWORD`, `TTOBAGI_DB_ROOT_PASSWORD`, `JWT_SECRET` | `openssl rand -hex 24` 등으로 서버에서 새로 만든다 |
| `TTOBAGI_AI_SERVER` | `http://host.docker.internal:18000` (SSH 터널 주소) |
| `TTOBAGI_AI_API_TOKEN`, `TTOBAGI_CALLBACK_TOKEN` | AI 서버와 같은 값 |
| `CORS_ALLOWED_ORIGINS` | FE 주소 (예: `https://아이디.github.io`) |
| `TTOBAGI_STORAGE_ENABLED` | `true`면 업로드 원본을 Object Storage에 보관 (아래 7번) |
| `TTOBAGI_STORAGE_ACCESS_KEY`, `TTOBAGI_STORAGE_SECRET_KEY` | Object Storage 권한만 있는 Sub Account의 API 인증키 |
| `TTOBAGI_STORAGE_UPLOAD_BUCKET`, `TTOBAGI_STORAGE_BACKUP_BUCKET` | `breathai-uploads`, `breathai-backup` |

키는 화면과 명령 기록에 남지 않게 입력받아 넣는다.

```bash
read -rp "Access Key ID: " AK; read -rsp "Secret Key: " SK; echo
# 이어서 .env에 TTOBAGI_STORAGE_ACCESS_KEY="$AK", TTOBAGI_STORAGE_SECRET_KEY="$SK"를 기록하고 unset AK SK
```

## 4. 실행

```bash
cd /opt/breathai/ttobagi-server
docker compose -f docker-compose.prod.yml up -d --wait mariadb     # DB만 먼저 (데이터 이관이 있으면 여기서 넣는다)
docker compose -f docker-compose.prod.yml up -d --build --wait app
curl -s http://127.0.0.1:8080/actuator/health                       # {"status":"UP"}
```

- 처음 빌드는 1~5분 걸린다.
- 이미 데이터가 있는 DB를 옮길 때는 BE를 띄우기 전에 덤프를 넣는다. 그다음 BE가 뜨면서 Flyway가 남은 마이그레이션을 적용한다.

## 5. HTTPS (IP 인증서)

도메인 없이 공인 IP로 Let's Encrypt 인증서를 받는다. IP 인증서는 유효기간이 6일이라 자동 갱신이 반드시 동작해야 한다.
Certbot 5.4 이상이 필요해 Ubuntu 기본 패키지(2.x) 대신 snap으로 설치한다.

```bash
apt-get install -y nginx
# NCP 서버는 IPv6가 꺼져 있어 기본 설정의 [::]:80 줄 때문에 nginx가 뜨지 않는다
sed -i '/listen \[::\]:80/d' /etc/nginx/sites-available/default
nginx -t && dpkg --configure -a

apt-get install -y snapd            # NCP 이미지에는 snap이 없다
snap wait system seed.loaded
snap install --classic certbot && ln -sf /snap/bin/certbot /usr/bin/certbot

IP=서버_공인_IP
certbot certonly --webroot -w /var/www/html --preferred-profile shortlived --ip-address $IP \
  --register-unsafely-without-email --agree-tos --non-interactive --dry-run      # 연습
certbot certonly --webroot -w /var/www/html --preferred-profile shortlived --ip-address $IP \
  --register-unsafely-without-email --agree-tos --non-interactive                # 실제 발급

cp /opt/breathai/ttobagi-server/deploy/nginx/breathai.conf /etc/nginx/sites-available/breathai
sed -i "s/211.233.202.231/$IP/g" /etc/nginx/sites-available/breathai             # 다른 IP라면
rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/breathai /etc/nginx/sites-enabled/breathai
nginx -t && systemctl reload nginx

# 갱신되면 nginx가 새 인증서를 읽게 한다
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
printf '#!/bin/sh\nsystemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
certbot renew --dry-run
systemctl list-timers | grep certbot      # snap.certbot.renew.timer
```

## 6. DB 백업

매일 04:00에 압축 덤프를 `/var/backups/breathai`에 남기고 14일 지난 것은 지운다.
`.env`에 Object Storage 키가 있으면 `breathai-backup` 버킷의 `db/`에도 올린다(버킷 수명 주기로 30일 뒤 자동 삭제).

```bash
install -m 700 /opt/breathai/ttobagi-server/deploy/backup/breathai-db-backup.sh /usr/local/bin/breathai-db-backup.sh
echo '0 4 * * * root /usr/local/bin/breathai-db-backup.sh >> /var/log/breathai-db-backup.log 2>&1' > /etc/cron.d/breathai-db-backup
/usr/local/bin/breathai-db-backup.sh && ls -l /var/backups/breathai/
```

업로드에는 AWS CLI 공식 도커 이미지(`amazon/aws-cli:2.22.35`)를 쓴다. 서버에 따로 설치하지 않는다.

## 7. Object Storage

| 버킷 | 내용 | 수명 주기 |
|---|---|---|
| `breathai-uploads` | 분석 업로드 원본 `uploads/연/월/업로드ID.확장자` | 90일 뒤 삭제 |
| `breathai-backup` | DB 백업 `db/ttobagi_db_날짜_시각.sql.gz` | 30일 뒤 삭제 |

- 둘 다 비공개, 잠금·암호화·버전 관리는 쓰지 않는다.
- 업로드 원본은 xlsx·csv 그대로(변환 전) 보관하고, 위치를 `gold_upload_file.file_path`에 `s3://버킷/키`로 기록한다.
- 보관에 실패해도 분석은 그대로 진행한다(서버 로그에 경고).
- 수명 주기로 지워진 뒤에도 `file_path`는 남는다. 위치 기록일 뿐 파일이 남아 있다는 뜻은 아니다.
- 인증키는 Sub Account(`NCP_OBJECT_STORAGE_MANAGER` 정책만)로 만든다. Sub Account의 접근 Source 제한은 Object Storage에 적용되지 않는다.

## 8. AI 서버 연결 (SSH 터널)

AI 서버(Vast.ai GPU)와는 이 서버가 여는 SSH 터널 하나로 주고받는다. 서버의 8080·3306을 밖에 열지 않고, AI 서버 주소가 바뀌어도 AI 쪽 설정은 그대로 둘 수 있다.

```
BE 컨테이너 ─ host.docker.internal:18000 ─▶ 서버 172.17.0.1:18000 ══(-L)══▶ Vast 127.0.0.1:18000  AI API(pipeline_api)
Vast AI(콜백) ─ 127.0.0.1:18080 ══(-R)══▶ 서버 127.0.0.1:8080   BE
Vast AI(DB)   ─ 127.0.0.1:13306 ══(-R)══▶ 서버 127.0.0.1:3306   MariaDB
```

- BE `.env`: `TTOBAGI_AI_SERVER=http://host.docker.internal:18000` (compose의 `extra_hosts`로 서버를 가리킨다). `-L`을 도커 bridge 주소(`172.17.0.1`)에 묶어야 BE 컨테이너가 닿는다.
- AI 쪽 설정: BE 주소 `http://127.0.0.1:18080`, DB `127.0.0.1:13306`, 계정 `ai_pipeline`. 터널 덕분에 AI 서버를 바꿔도 이 값은 그대로다.

### 8-1. 키

```bash
ssh-keygen -t ed25519 -f /root/.ssh/breathai_vast_tunnel -N "" -C breathai-ncp-tunnel
cat /root/.ssh/breathai_vast_tunnel.pub
```

공개키(`.pub` 한 줄)를 AI 담당에게 보내 Vast 인스턴스의 `/root/.ssh/authorized_keys`에 넣어 달라고 한다(Vast 콘솔의 SSH 키 추가 메뉴도 된다). 공개키는 알려져도 괜찮고, 개인키는 서버 밖으로 내보내지 않는다.

서비스는 `StrictHostKeyChecking=yes`라 처음 한 번은 직접 접속해 서버 지문을 저장해야 한다.

```bash
ssh -i /root/.ssh/breathai_vast_tunnel -p 포트 -o StrictHostKeyChecking=accept-new root@IP 'hostname'
```

### 8-2. 서비스

`/etc/systemd/system/breathai-tunnel.service`

```ini
[Unit]
Description=BreathAI SSH tunnel to Vast.ai AI server
After=network-online.target docker.service
Wants=network-online.target

[Service]
ExecStart=/usr/bin/ssh -N -i /root/.ssh/breathai_vast_tunnel -p 34034 \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes \
  -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -L 172.17.0.1:18000:127.0.0.1:18000 \
  -R 127.0.0.1:18080:127.0.0.1:8080 \
  -R 127.0.0.1:13306:127.0.0.1:3306 \
  root@171.235.173.155
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now breathai-tunnel
```

- 끊기면 10초마다 다시 붙는다(`Restart=always`). 서버를 껐다 켜도 자동으로 시작한다.
- `ExitOnForwardFailure=yes`: 포워딩 하나라도 실패하면 접속을 끊고 다시 시도한다. 반쯤 붙은 상태로 남지 않게 하기 위해서다.
- 포트(`-p`)와 `root@IP`는 AI 서버를 빌릴 때마다 바뀐다(8-5).

### 8-3. AI 전용 DB 계정

AI는 Bronze 원본 적재와 FAQ 읽기에 필요한 표만 쓸 수 있다. 비밀번호는 이 문서에 적지 않고 AI 담당에게 직접 전달한다.

```sql
CREATE USER 'ai_pipeline'@'%' IDENTIFIED BY '비밀번호';
GRANT SELECT ON `ttobagi_db`.`gold_faq` TO 'ai_pipeline'@'%';
GRANT SELECT ON `ttobagi_db`.`bronze_cate_info` TO 'ai_pipeline'@'%';
GRANT SELECT ON `ttobagi_db`.`bronze_counselling_info` TO 'ai_pipeline'@'%';
GRANT SELECT, INSERT, UPDATE ON `ttobagi_db`.`bronze_ingest_batch` TO 'ai_pipeline'@'%';
GRANT SELECT, INSERT, UPDATE ON `ttobagi_db`.`bronze_unanswered_learning` TO 'ai_pipeline'@'%';
```

권한 확인: `SHOW GRANTS FOR 'ai_pipeline'@'%'` (계정·분석 표 등 나머지 `gold_*`에는 접근할 수 없어야 한다)

### 8-4. 확인

```bash
cd /opt/breathai/ttobagi-server && C="docker compose -f docker-compose.prod.yml"
systemctl is-active breathai-tunnel                                                  # active
$C exec -T app curl -s -o /dev/null -w "%{http_code}\n" http://host.docker.internal:18000/   # 404면 연결됨, 000이면 안 됨
ssh -i /root/.ssh/breathai_vast_tunnel -p 34034 -o BatchMode=yes -o ConnectTimeout=15 root@171.235.173.155 \
  'echo 접속됨; pgrep -fa "pipeline_api|pipeline_worker" | head -3'                 # 접속 + AI 프로그램 2개
journalctl -u breathai-tunnel -n 20 --no-pager                                       # 최근 기록
```

AI 쪽에서는 `curl -s http://127.0.0.1:18080/actuator/health`가 BE의 `UP`을 돌려주면 콜백 길이 열린 것이다.

### 8-5. AI 서버를 바꿨을 때 (주소 변경)

Vast는 대여 기간이 끝나거나 다른 GPU로 옮기면 IP·포트가 바뀐다. 연결 방식은 그대로 두고 주소만 바꾼다.

1. AI 담당이 새 인스턴스에 공개키(8-1)를 등록하고, AI 프로그램(`pipeline_api`, `pipeline_worker`)을 띄운다.
2. 새 주소로 지문을 저장한다: `ssh -i /root/.ssh/breathai_vast_tunnel -p 새포트 -o StrictHostKeyChecking=accept-new root@새IP 'hostname'`
3. 서비스 파일에서 `-p 포트 \` 줄과 `root@IP` 줄만 바꾼다.
4. `systemctl daemon-reload && systemctl restart breathai-tunnel`
5. 8-4로 `404`를 확인한다.

### 8-6. 문제별 대처

| 증상 (`journalctl` 또는 직접 SSH) | 원인 | 대처 |
|---|---|---|
| `Permission denied (publickey)` | Vast 인스턴스에 공개키가 없다(인스턴스를 새로 만들거나 키가 지워짐) | AI 담당에게 공개키(8-1) 재등록 요청 |
| `Connection refused`, `Connection timed out` | 인스턴스가 꺼졌거나 IP·포트가 바뀜 | AI 담당에게 현재 주소 확인 → 8-5 |
| `REMOTE HOST IDENTIFICATION HAS CHANGED` | 같은 IP·포트에 새 인스턴스가 들어옴 | `ssh-keygen -R "[IP]:포트"` 후 8-5의 2번 |
| `remote port forwarding failed` | 이전 접속이 Vast에 남아 18080·13306을 잡고 있음 | 잠시 뒤 자동 재시도로 풀린다. 계속되면 AI 담당이 남은 `sshd` 세션을 끊는다 |
| 서비스는 `active`인데 BE→AI가 `000` | AI 프로그램이 꺼짐 | AI 담당이 `pipeline_api`·`pipeline_worker`를 다시 띄운다 |

터널이 끊긴 동안 올린 분석은 AI에 요청하지 못하고 바로 실패(`FAIL`)로 끝난다. 다시 올리면 같은 파일이라도 새로 분석한다.

## 9. 업데이트

```bash
cd /opt/breathai && git pull
cd ttobagi-server && docker compose -f docker-compose.prod.yml up -d --build --wait app
```

## 10. 겪은 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| `./gradlew: Permission denied` | 저장소에 실행 권한 없이 기록됨 (Windows 빌드에서는 드러나지 않음) | #51에서 100755로 기록 |
| nginx 설치 중 시작 실패 `[::]:80 failed` | 서버에 IPv6가 없음 | 기본 설정에서 `[::]:80` 줄 삭제 |
| `snap: command not found` | NCP Ubuntu 이미지에 snapd 없음 | `apt-get install -y snapd` |
| `.env` 값 끝에 보이지 않는 문자 | Windows에서 복사한 BOM·CRLF | `sed`로 제거 |
| apt 업그레이드 중 설정 파일 질문 | NCP가 바꿔 둔 설정 파일 | `N`(현재 유지), 설치 시 `--force-confold` |
| 10-19~10-26 AI 연결 끊김(BE→AI `000`, 터널 재시작 6만 번 넘게 반복) | Vast 인스턴스에 터널 공개키가 없어 새 접속이 `Permission denied (publickey)`. 그전에 붙어 있던 접속이 끊긴 뒤로 다시 붙지 못함 | AI 담당이 공개키 재등록(8-1). 주기적으로 8-4 확인 |
