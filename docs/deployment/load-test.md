# AWS 실제 사용자 부하 테스트

Issue #710의 AWS 공유 개발 서버 성능 검증 절차다. Android의 대표적인 읽기 흐름을 재현하되 운영 데이터 변경과 외부 provider 호출은 제외한다.

## 실행 환경

- 실행일: 2026-09-18
- 부하 발생기: 개발자 Windows PC의 Docker Desktop
- 대상: AWS 공유 개발 환경의 EC2 `t3.micro`(2 vCPU, 1GiB)와 RDS `db.t4g.micro`
- API 실행 형태: Uvicorn 단일 process

## 검증 범위

한 virtual user는 다음 흐름을 반복한다.

```text
여행 목록
→ 여행 상세 + 전체 일정
→ 당일 진행 상태 + 저장된 경로
```

- 각 화면 이동 사이에는 1~3초의 think time을 둔다.
- 상세와 일정, 진행 상태와 경로는 Android 화면과 같이 병렬 요청한다.
- 모든 요청은 Bearer 인증과 RDS 조회를 포함한다.
- 장소 검색, 경로 재계산, 진행 변경, FCM 발송은 호출하지 않는다.

## 안전 기준

다음 순서대로 각 단계를 5분간 별도로 실행한다.

```text
5명 → 10명 → 20명 → 50명 → 100명
```

아래 기준을 하나라도 위반하면 k6가 해당 단계를 중단하며 다음 단계로 진행하지 않는다.

- HTTP 오류율 1% 미만
- check 성공률 99% 초과
- 전체 요청 p95 1초 미만

테스트 중 EC2 CPU가 80% 이상으로 지속되거나 사용 가능 메모리가 100MiB 미만이면 k6를 `Ctrl+C`로 중단한다. API·Caddy container 재시작, health check 실패, HTTP 500·502가 발생해도 즉시 중단한다.

## 사전 준비

1. EC2가 아닌 개발자 PC에서 Docker Desktop을 실행한다.
2. 부하 테스트 전용 계정에 조회 가능한 여행과 날짜별 일정·경로를 준비한다.
3. 1시간 안에 만료되지 않을 Access Token을 PowerShell 환경변수에 직접 입력한다. Token을 명령 인자, 파일, Issue, PR, 로그에 기록하지 않는다.
4. 저장소 root에서 결과용 임시 디렉터리를 만든다.

```powershell
$env:GILPICK_LOAD_ACCESS_TOKEN = Read-Host "Access Token"
$env:GILPICK_LOAD_TRIP_ID = "<전용 테스트 여행 UUID>"
$env:GILPICK_LOAD_VISIT_DATE = "<YYYY-MM-DD>"
New-Item -ItemType Directory -Force .tmp/load-results | Out-Null
```

Access Token TTL은 1시간이다. 전체 단계를 연속 실행하면 만료될 수 있으므로 단계마다 유효성을 확인하고, 401이 발생하면 새 Token으로 교체한다.

## 실행

먼저 5명 단계만 실행한다. 성공한 단계의 다음 인원으로 `TARGET_VUS`와 결과 파일명만 바꾼다.

```powershell
docker run --rm `
  -e BASE_URL=https://gilpick-api.duckdns.org/api/v1 `
  -e ACCESS_TOKEN=$env:GILPICK_LOAD_ACCESS_TOKEN `
  -e TRIP_ID=$env:GILPICK_LOAD_TRIP_ID `
  -e VISIT_DATE=$env:GILPICK_LOAD_VISIT_DATE `
  -e TARGET_VUS=5 `
  -e DURATION=5m `
  -v "${PWD}/api/tests/load:/scripts:ro" `
  -v "${PWD}/.tmp/load-results:/results" `
  grafana/k6 run --quiet --summary-export=/results/5-users.json /scripts/aws_read_flow.js
```

테스트가 끝나면 환경변수를 제거한다.

```powershell
Remove-Item Env:GILPICK_LOAD_ACCESS_TOKEN
Remove-Item Env:GILPICK_LOAD_TRIP_ID
Remove-Item Env:GILPICK_LOAD_VISIT_DATE
```

## 서버 관찰

테스트와 동시에 EC2의 별도 SSH 창에서 실행한다. 화면 확인만 필요하면 첫 명령을 사용하고, 결과를 남길 때는 두 번째 명령으로 5초마다 표본을 기록한다.

```bash
cd /opt/gilpick/repository
sudo docker stats aws-api-1 aws-caddy-1

while true; do
  date --iso-8601=seconds
  free -m | sed -n '2p'
  sudo docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} memory={{.MemUsage}}'
  sleep 5
done | tee /tmp/gilpick-load-metrics.log
```

각 단계가 끝난 뒤 상태와 오류를 확인한다.

```bash
free -h
sudo docker compose -f deploy/aws/compose.yaml ps
sudo docker compose -f deploy/aws/compose.yaml logs --since=10m api caddy 2>&1 | grep -E ' 500 | 502 |"status":(500|502)|ERROR|Exception'
curl --fail https://gilpick-api.duckdns.org/health
```

AWS CloudWatch에서도 EC2의 `CPUUtilization`·`CPUCreditBalance`와 RDS의 `CPUUtilization`·`DatabaseConnections`·`FreeableMemory`를 같은 시간대로 확인한다. `t3.micro`의 짧은 burst 결과만으로 지속 처리 용량을 과대평가하지 않는다.

## 결과 기록

동일 계정을 공유한 virtual user 결과는 `동시 사용자`가 아니라 `동일 계정 기반 동시 세션`으로 표현한다. 아래 표에 k6 summary와 EC2 관찰값을 기록한다.

| 동시 세션 | 지속시간 | 총 요청 | 오류율 | 평균 | p95 | 최대 | 최대 API CPU | 최소 available memory | 결과 |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 5 | 5분 | 1,180 | 0% | 117.81ms | 397.74ms | 556.56ms | 73.04% | 215MiB | 통과 |
| 10 | 5분 | 2,390 | 0% | 116.62ms | 382.90ms | 880.56ms | 78.42% | 207MiB | 통과 |
| 20 | 5분 | 4,745 | 0% | 125.50ms | 432.81ms | 2,664.79ms | 81.34% | 208MiB | 통과 |
| 50 | 5분 | 11,515 | 0% | 178.66ms | 609.01ms | 3,623.85ms | 115.52% | 193MiB | 통과 |
| 100 | 31초(자동 중단) | 617 | 31.11% | 3,662.41ms | 10,001.06ms | 10,002.34ms | 100.21% | 180MiB | 실패 |

`/health` 사전 smoke test에서 동시 작업 10개·총 200건은 오류 0건, 평균 154ms, p95 386ms, 최대 433ms였다. 이 결과는 인증과 DB를 포함하지 않으므로 실제 사용자 처리 성능으로 간주하지 않는다.

20 세션의 API CPU 최대값 81.34%는 5초 간격 46개 표본 중 한 번만 관측된 순간치다. CPU p95는 55.63%였으며 80% 이상 사용이 지속되지는 않았다. 테스트 중 AWS 배포와 겹친 실행은 용량 판정에서 제외하고 배포 완료 후 다시 측정했다.

50 세션에서는 API container CPU가 평균 59.80%, p95 108.09%, 최대 115.52%였다. `docker stats` CPU는 vCPU 하나를 100%로 표시하므로 2 vCPU인 현재 EC2의 전체 CPU 사용률과 동일한 지표가 아니다. HTTP 기준과 메모리 기준은 통과했고 container 재시작과 health check 실패는 없었다.

100 세션에서는 요청 timeout이 이어져 오류율과 p95 기준을 위반했고 k6가 약 31초 만에 자동 중단했다. 같은 시간의 EC2 전체 CPU 최대값은 55.50%, 사용 가능 메모리 최솟값은 180MiB였으며 API·Caddy container 재시작은 없었다. 종료 후 `/health`는 다시 `200 OK`였다. API 로그에는 다수의 `TimeoutError`가 기록됐다.

따라서 이 시나리오에서 5분간 안정적으로 처리한 최대 검증값은 **동일 계정 기반 동시 세션 50개**다. 실제 한계가 정확히 50명이라는 뜻은 아니며, 검증 결과로는 50개는 통과하고 100개는 실패했다는 범위만 확정할 수 있다. CPU와 메모리가 중단 기준에 도달하기 전에 timeout이 발생했으므로 DB connection pool 또는 단일 API process의 동시 처리 대기가 병목일 가능성이 있지만, 이는 **추측**이며 별도 계측 없이는 원인을 확정할 수 없다.
