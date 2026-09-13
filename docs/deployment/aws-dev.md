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

## 6. 공모전 종료

1. 필요한 RDS snapshot을 만들고 개인정보 보존·삭제 정책을 처리한다.
2. EC2, RDS, EBS, snapshot, 탄력적 IP를 정확히 확인한 뒤 삭제한다.
3. Caddy volume과 CloudWatch log 등 잔여 과금 리소스를 확인한다.
4. Kakao callback 허용값을 제거하고 외부 API key와 JWT signing secret을 폐기·회전한다.
