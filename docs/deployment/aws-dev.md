# AWS 공유 개발 환경 배포

Issue #418의 EC2 + RDS 개발 환경 배포 절차다. 실제 secret, RDS endpoint, 탄력적 IP와 `.pem` 파일은 저장소에 기록하지 않는다.

## 구성

- EC2: Ubuntu Server 24.04 LTS, `t3.micro`, 15 GiB gp3
- RDS: PostgreSQL 18, `db.t4g.micro`, Single-AZ, 비공개 접근
- TLS: Caddy 자동 HTTPS
- 애플리케이션: FastAPI 단일 worker

알림·감지 background job의 중복 실행을 막기 위해 API는 단일 worker로 실행한다.

## 1. 사전 확인

1. RDS 보안 그룹의 PostgreSQL 5432 inbound source가 EC2 보안 그룹으로만 제한됐는지 확인한다.
2. EC2 보안 그룹은 HTTP 80과 HTTPS 443을 공개하고 SSH 22는 담당자 IP로만 제한한다.
3. 사용할 API domain의 DNS `A` record를 EC2 탄력적 IP로 지정한다.
4. Kakao Developers의 Redirect URI를 `https://<API_DOMAIN>/api/v1/auth/kakao/callback`으로 등록한다.

## 2. 서버 준비

EC2에서 저장소를 `/opt/gilpick/repository`에 checkout하고 실제 환경 파일을 저장소 밖에 만든다.

```bash
sudo mkdir -p /opt/gilpick
sudo chown ubuntu:ubuntu /opt/gilpick
cp /opt/gilpick/repository/api/.env.example /opt/gilpick/.env
chmod 600 /opt/gilpick/.env
```

`/opt/gilpick/.env`에서 다음 값을 실제 dev 값으로 바꾼다. `API_DOMAIN`은 scheme이나 path가 없는 host만 쓴다.

```dotenv
API_DOMAIN=api-dev.example.com
DATABASE_URL=postgresql+asyncpg://gilpick_app:<URL_ENCODED_PASSWORD>@<RDS_ENDPOINT>:5432/gilpick?ssl=require
JWT_ISSUER=https://api-dev.example.com
KAKAO_REDIRECT_URI=https://api-dev.example.com/api/v1/auth/kakao/callback
ANDROID_APP_LINK_BASE_URL=https://gilpick.pages.dev/auth/kakao/complete
ANDROID_APP_LINK_HOST=gilpick.pages.dev
```

DB 암호에 예약문자가 있으면 URL encoding한다. 나머지 API key와 FCM 값도 `/opt/gilpick/.env`에만 입력한다.

### Google Places 설정

공유 개발 환경의 Firebase와 Google Places는 Google Cloud 프로젝트 `gilpick-85911`에서 함께 관리한다. Android·Browser용 Firebase 자동 생성 key를 Backend에서 재사용하지 않고, Backend 전용 key를 별도로 만든다.

- `Places API (New)`와 billing을 활성화한다.
- 애플리케이션 제한은 EC2 탄력적 공인 IP로 설정한다.
- API 제한은 `Places API (New)`로 설정한다.
- key 원문은 `/opt/gilpick/.env`에만 저장하고 저장소·Issue·PR·로그에 남기지 않는다.
- 설정 변경 후 API container를 강제 재생성해야 새 값이 주입된다.

```bash
docker compose -f deploy/aws/compose.yaml up -d --no-deps --force-recreate api
docker compose -f deploy/aws/compose.yaml ps
```

Google Places smoke test는 key나 provider 응답 본문을 출력하지 않고 HTTP 상태와 결과 개수만 확인한다.

```bash
docker compose -f deploy/aws/compose.yaml exec -T api .venv/bin/python -c 'import json,os,urllib.request; body=json.dumps({"textQuery":"서울 카페","maxResultCount":1}).encode(); req=urllib.request.Request("https://places.googleapis.com/v1/places:searchText",data=body,headers={"Content-Type":"application/json","X-Goog-Api-Key":os.environ["GOOGLE_PLACES_API_KEY"],"X-Goog-FieldMask":"places.id"},method="POST"); res=urllib.request.urlopen(req,timeout=5); data=json.load(res); print({"status":res.status,"placeCount":len(data.get("places",[]))})'
```

정상 기준은 `status: 200`이고 `placeCount`가 1 이상인 것이다. 오류 로그에는 내부 `error_code`와 `provider_status`만 기록하며 key, 요청 header와 provider 응답 본문은 기록하지 않는다.

## 3. migration과 실행

저장소 root에서 실행한다.

```bash
docker compose -f deploy/aws/compose.yaml build api
docker compose -f deploy/aws/compose.yaml run --rm api .venv/bin/alembic upgrade head
docker compose -f deploy/aws/compose.yaml up -d
docker compose -f deploy/aws/compose.yaml ps
```

배포 확인:

```bash
curl --fail https://<API_DOMAIN>/health
docker compose -f deploy/aws/compose.yaml run --rm api .venv/bin/alembic current
```

`/health`가 `{"status":"ok"}`를 반환하고 Alembic revision이 head이면 Backend 기반 검증을 진행한다.

## 4. Android 연결

release 후보를 실제 API 주소로 빌드한다.

```powershell
cd android
.\gradlew.bat :app:assembleDebug -PGILPICK_API_BASE_URL=https://<API_DOMAIN>/api/v1/
```

실제 release 제출물은 동일 주소를 사용하되 release signing과 App Link 검증을 별도로 수행한다.

## 5. 갱신과 rollback

코드 갱신 시 최신 commit을 checkout한 뒤 migration, build, 재기동 순으로 실행한다.

```bash
docker compose -f deploy/aws/compose.yaml run --rm api .venv/bin/alembic upgrade head
docker compose -f deploy/aws/compose.yaml up -d --build
```

문제가 생기면 직전 검증 commit을 checkout하고 다시 build한다. DB migration downgrade는 데이터 손실 가능성이 있어 자동 rollback하지 않는다.

## 5-1. GitHub Actions로 배포(수동 트리거)

Issue #523. 위 5절과 같은 절차(migration → build → 재기동 → health check)를 GitHub Actions 탭에서 버튼 한 번으로 실행할 수 있다. `.github/workflows/deploy-aws.yml`이 `workflow_dispatch`로만 동작하며, main merge마다 자동으로는 실행되지 않는다 — 공유 PoC 서버가 무관한 merge로 재시작돼 다른 팀원의 검증을 끊는 걸 피하기 위해서다.

이 저장소는 **Public**이다. self-hosted runner를 public repo에서 쓰면 외부인이 연 PR의 workflow가 우리 서버에서 실행될 수 있어 GitHub이 원칙적으로 비권장한다. `deploy-aws.yml`은 `workflow_dispatch`만 트리거라 Write 권한 있는 팀원만 실행할 수 있어 이 위험에서 벗어나 있다. **앞으로 다른 workflow에 `self-hosted`·`aws-dev` label을 재사용하려면, 반드시 `workflow_dispatch`처럼 외부에서 트리거 불가능한 이벤트로만 제한한다. `pull_request`·`pull_request_target`에는 절대 쓰지 않는다.**

EC2 보안 그룹이 SSH 22를 담당자 IP로만 제한하고 있어(1절), IP가 매번 바뀌는 GitHub 호스팅 runner에서는 SSH 접속이 막힌다. 그래서 SSH 대신 **EC2 안에 self-hosted runner를 직접 설치**해 실행한다 — 이러면 SSH secret 자체가 필요 없다.

### 최초 1회: self-hosted runner 설치

**runner 등록 token 발급은 Admin 권한이 필요하다.** repo owner가 GitHub 웹에서 `Settings → Actions → Runners → New self-hosted runner` (Linux, x64)를 열어 표시되는 `--token` 값을 확인한 뒤, EC2 SSH 접속 권한이 있는 담당자에게 전달한다. 이 token은 짧은 시간 안에 만료되는 1회성 등록 token이라 SSH key와 달리 일반 채널로 공유해도 되지만, 저장소나 로그에는 남기지 않는다.

EC2에서 실행(다운로드 URL·token은 GitHub이 보여주는 값 그대로 사용):

```bash
mkdir -p /opt/gilpick/actions-runner && cd /opt/gilpick/actions-runner
curl -o actions-runner.tar.gz -L https://github.com/actions/runner/releases/download/<최신 버전>/actions-runner-linux-x64-<최신 버전>.tar.gz
tar xzf actions-runner.tar.gz
./config.sh --url https://github.com/angry-cat55/gil-pick --token <GitHub이 보여준 token> --labels aws-dev --unattended
sudo ./svc.sh install
sudo ./svc.sh start
```

`svc.sh install`로 systemd service로 등록하면 EC2 재부팅 후에도 runner가 자동으로 다시 뜬다. GitHub repository → Settings → Actions → Runners에서 `Idle` 상태로 뜨면 설치 완료다.

### 실행

1. GitHub repository → Actions 탭 → `Deploy to AWS (dev)` workflow → `Run workflow`.
2. `confirm` 입력란에 정확히 `deploy`를 입력해야 실행된다. 다른 값이면 즉시 실패한다.
3. 실행 후 health check까지 통과하면 초록불, 실패하면 빨간불로 멈춘다.

### 실패했을 때

자동 rollback은 하지 않는다. EC2에 직접 접속해 원인을 본다.

```bash
docker compose -f deploy/aws/compose.yaml logs api
docker compose -f deploy/aws/compose.yaml run --rm api .venv/bin/alembic current
```

migration까지는 성공하고 build·health check만 실패했다면 원인 수정 후 5절 절차나 workflow를 다시 실행하면 된다. migration 자체가 문제라면 downgrade는 데이터 손실 위험이 있으므로 팀 논의 후 수동으로 판단한다.

## 6. 공모전 종료

1. 필요한 RDS snapshot을 만들고 개인정보 보존·삭제 정책을 처리한다.
2. EC2, RDS, EBS, snapshot, 탄력적 IP를 정확히 확인한 뒤 삭제한다.
3. Caddy volume과 CloudWatch log 등 잔여 과금 리소스를 확인한다.
4. Kakao callback 허용값을 제거하고 외부 API key와 JWT signing secret을 폐기·회전한다.
