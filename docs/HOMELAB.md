# 홈서버 — 측정 전용 환경

성능 숫자를 재는 곳이다. 노트북은 개발과 부하 생성에 쓰고, **재는 대상은 여기에 둔다.**

## 왜 옮겼나

노트북에서 재면 **부하 생성기와 측정 대상이 같은 CPU를 두고 싸운다.**
2026-08-22 baseline에서 그 대가를 치렀다 — 원장 조회 시나리오의 p99가 8초까지 갔는데
정작 `ledger` 프로세스의 CPU는 4.9%였고 호스트가 91%였다. **원장이 아니라 노트북을
재고 있었던 것**이라 그 시나리오는 baseline에서 통째로 빼야 했다.

k6를 `--cpus="4"`로 묶자 같은 부하에서 처리량이 211 → 346 req/s로 올랐다.
**부하를 거는 쪽을 굶겼더니 측정 대상이 그제야 일을 했다**는 뜻이다.

## 이 머신

| | |
|---|---|
| CPU | AMD Ryzen 5 3600 — **6코어 / 12스레드** |
| 메모리 | **31GB** (2026-09-05 실측 `free -h`) |
| 디스크 | **476GB NVMe** — 루트 466GB 중 **여유 399GB** (2026-09-05 실측) |
| OS | Ubuntu 24.04 LTS |
| 접속 | `ssh home1` (집 안) / `ssh home2` (집 밖) — 아래 "주소가 둘인 문제" 참고 |
| 작업 경로 | `~/remittance` |

**코어 수는 노트북과 같다.** 그러니 여기의 이점은 코어가 많아서가 아니다.

| 얻는 것 | |
|---|---|
| **전용** | IDE·브라우저가 없어 측정할 때마다 조건이 같다 |
| **리눅스** | `cpuset`이 실제로 듣고, Docker Desktop VM 오버헤드가 없다 |
| **시각 정밀도** | `Instant.now()`가 나노초까지 나와, macOS에서 재현 안 되던 버그를 로컬에서 잡는다 (`AGENTS.md` 참고) |
| **상시 가동** | Phase 8의 ArgoCD처럼 계속 살아 있어야 의미 있는 것들을 올릴 수 있다 |
| **호스트 지표** | `node-exporter`가 붙어 있어 "앱이 느린가 머신이 느린가"를 화면에서 바로 가른다 |

## 부하를 어디서 거나

**기본은 서버 안에서 코어를 갈라 건다** (서비스 0~9번, k6 10~11번).

처음에는 노트북에서 거는 쪽을 기본으로 잡았는데, **노트북이 WiFi로 붙어 있어 RTT가
접수 지연에 그대로 더해진다.** 그런데 그 접수 지연(커넥션 풀 고갈)이 baseline의 핵심
발견이라 오염시킬 수 없었다. 리눅스에서는 코어 고정이 실제로 들으므로, 한 머신
안에서도 자원 분리가 된다.

> k6에 코어를 2개 주든 4개 주든 결과가 같은 것을 확인했다(400.6 vs 402.8 req/s).
> 부하 생성기가 병목이 아니라는 뜻이라, 2개로 충분하다.

노트북과 서버는 WiFi로 붙어 있는데, **정작 중요한 숫자는 영향을 받지 않는다.**

| 측정 대상 | WiFi 영향 |
|---|---|
| 종결 처리량, Outbox 적체, 락 대기, 커넥션 풀 | **없음** — 접수된 뒤 서버 안에서만 도는 값이다 |
| 접수 지연 p95/p99 | **있음** — RTT와 지터가 그대로 더해진다 |

접수 지연을 정밀하게 봐야 할 때만 **서버 안에서** k6를 돌리고, 그때는 코어를 갈라 쓴다.

```bash
# 서버 안에서 돌릴 때: k6는 10~11번 코어, 서비스는 0~9번
ssh home1 'cd ~/remittance && CPUSET=0-9 ./scripts/homelab-services.sh restart'
ssh home1 'cd ~/remittance && docker run --rm -i --network host --cpuset-cpus="10-11" \
  -v "$PWD:/work" -w /work grafana/k6:latest run load-test/scenarios/spread.js'
```

## 절차

### 1. 소스를 보낸다

노트북에서 (아직 푸시하지 않은 커밋도 그대로 넘어간다):

```bash
rsync -az --delete --exclude 'build/' --exclude '.gradle/' --exclude '.idea/' \
  ./ home1:~/remittance/
```

### 2. 빌드 — 호스트에 JDK가 없다

이 머신에는 Java를 깔지 않았다. sudo가 필요하고, 측정용 머신은 상태가 단순한 편이
낫기 때문이다. **빌드는 JDK 컨테이너 안에서** 한다.

```bash
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh build'
```

> 컨테이너 안에는 `git`이 없어서 build-info의 커밋이 `unknown`으로 떨어진다.
> 스크립트가 **호스트에서 커밋을 읽어 `-PgitCommit`으로 넘긴다.**
> 이게 없으면 "지금 떠 있는 게 어느 커밋이냐"에 답할 수 없고, build-info를 심어둔
> 이유 자체가 사라진다. Phase 7의 이미지 빌드에서도 같은 문제를 만난다.

### 3. 인프라와 서비스를 띄운다

```bash
ssh home1 'cd ~/remittance && docker compose -f docker-compose.dev.yml -f docker-compose.homelab.yml up -d'
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh start'
```

서비스는 이미지를 만들지 않고 **JRE 컨테이너에 jar만 마운트**해 띄운다
(`--network host`라 노트북에서 `java -jar`로 띄우던 것과 같은 그림이다).
Phase 7에서 서비스마다 제대로 된 Dockerfile을 쓸 때까지의 임시 방편이다.

#### 같은 서비스를 여러 벌 띄우려면 — `REPLICAS`

**인스턴스가 하나면 존재할 수 없는 결함**을 보려면 진짜로 두 벌을 띄워야 한다.
Outbox 릴레이의 중복 발행이 그런 종류다.

```bash
ssh home1 'cd ~/remittance && REPLICAS="transfer-service=2 account-service=2" \
  CPUSET=0-9 ./scripts/homelab-services.sh restart'
```

`--network host`라 포트가 곧 주소이므로 **2번째부터 +100**으로 갈린다(8082 → 8182).
게이트웨이는 기본 포트만 알기 때문에 **추가 인스턴스는 HTTP를 받지 않고**
Kafka 소비와 Outbox 릴레이에만 참여한다.

> ⚠️ **`stop`·`restart`에도 `REPLICAS`를 붙일 필요는 없지만, 붙이는 습관이 안전하다.**
> 스크립트는 이름으로 훑어 `remittance-<모듈>`과 `remittance-<모듈>-N`을 전부 내린다.
> 예전에는 `REPLICAS` 없이 `restart`하면 `-2`가 살아남았고, **"1대로 되돌렸다"고 믿으며
> 2대로 측정**하는 사고가 났다(2026-09-05). 측정 전 `status`가 몇 줄을 찍는지
> 세어보는 것이 가장 확실하다.

**두 인스턴스가 정말 둘 다 일하는지**는 발행량으로 확인한다. 한쪽이 0이면
그 측정은 2대를 잰 것이 아니다.

```bash
ssh home1 'for p in 8081 8181 8082 8182; do echo -n "  :$p "; \
  curl -s localhost:$p/actuator/prometheus \
  | awk "/^kafka_producer_record_send_total/ {s+=\$2} END {print s+0}"; done'
```

### 4. 측정 전에 반드시 — 떠 있는 게 내가 만든 것인가

```bash
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh status'
```

여섯 서비스의 `/actuator/info`가 HEAD와 같은지 확인한다.
**2026-08-22 baseline 1차를 낡은 jar로 재고 전부 버린 적이 있다.** 그때 `/actuator/info`는
정직하게 옛 커밋을 답하고 있었는데 물어보지 않았을 뿐이다.

### 5. 화면 — IP를 치지 말고 SSH 터널로 본다

```bash
ssh -N -L 3000:localhost:3000 -L 9090:localhost:9090 home1   # 집 안
ssh -N -L 3000:localhost:3000 -L 9090:localhost:9090 home2   # 집 밖
```

띄워둔 채 노트북에서 http://localhost:3000 (Grafana), http://localhost:9090 (Prometheus)을 연다.

**IP를 문서나 스크립트에 박지 않는다.** 집 안(`192.168.55.x`)과 집 밖(공인 IP)에서
주소가 다르고, 공인 IP는 ISP가 바꿀 수 있다. `~/.ssh/config`의 별칭만 쓰면
주소가 바뀌어도 그 파일 한 곳만 고치면 된다.

> ⚠️ **이 포트들을 공유기에서 인터넷으로 포워딩하지 않는다.**
> 우리 Grafana는 `GF_AUTH_ANONYMOUS_ENABLED=true`에 **Admin 권한**으로 떠 있고,
> `/actuator`가 열려 있고, MySQL은 `root/root`이다. 로컬 측정용이라 그렇게 둔 것이지
> 인터넷에 내놓을 수 있는 상태가 아니다. 터널은 이미 인증된 SSH를 타므로
> 아무것도 새로 열지 않는다.

## 주소가 둘인 문제 — 집 밖에서는 부하를 서버 안에서 건다

이 머신은 집 안에서는 `192.168.55.167`, 집 밖에서는 공인 IP로 붙는다.
`~/.ssh/config`에 `home1`(집 안)·`home2`(집 밖) 별칭이 있으므로 **접속과 조작은 그대로**다.
빌드·기동·측정을 전부 SSH로 하기 때문에 주소가 바뀌어도 절차가 같다.

**달라지는 건 부하를 어디서 거느냐 하나다.**

| 있는 곳 | 부하 생성기 | 이유 |
|---|---|---|
| 집 안 | **노트북** (기본) | 유선/무선이라도 LAN이라 지연이 짧고, 자원이 완전히 분리된다 |
| 집 밖 | **서버 안에서** `CPUSET`으로 분리 | 인터넷을 넘어 부하를 걸면 병목이 앱인지 회선인지 알 수 없다 |

```bash
# 집 밖에서 잴 때: 서비스는 0~9번 코어, k6는 10~11번 코어
ssh home2 'cd ~/remittance && CPUSET=0-9 ./scripts/homelab-services.sh restart'
ssh home2 'cd ~/remittance && docker run --rm -i --network host --cpuset-cpus="10-11" \
  -v "$PWD:/work" -w /work grafana/k6:latest run load-test/scenarios/spread.js'
```

> **8081~8085를 인터넷에 열어 노트북에서 때리는 방식은 쓰지 않는다.**
> 측정이 무의미해지고(RTT·업로드 대역폭이 그대로 섞임) 보안도 나쁘다.
>
> 다만 **같은 시나리오라도 부하를 건 위치가 다르면 비교하면 안 된다.**
> 노트북에서 잰 값과 서버 안에서 잰 값은 접수 지연이 다르다
> (종결 처리량·적체·락 대기 같은 서버 내부 지표는 영향받지 않는다).
> 측정 기록에 **어디서 걸었는지**를 함께 남긴다.

## 메모리를 손으로 묶는 이유

이 머신은 **CPU는 남고 메모리가 빠듯하다.** 기본값대로 두면,

- MongoDB가 WiredTiger 캐시로 `(RAM-1GB)의 50%` = **약 7GB**를 가져간다
- JVM 다섯 개가 각자 사용 가능 메모리의 1/4을 힙 상한으로 잡는다

합치면 물리 메모리를 넘겨 스왑으로 떨어진다. **성능을 재려는 머신에서 스왑은 측정 실패다.**
그래서 `docker-compose.homelab.yml`과 `scripts/homelab-services.sh`가 전부 명시적으로 묶는다
(합계 약 8GB, 실측 사용 5GB / 여유 9GB).

> ⚠️ **위 문단은 메모리가 15GB이던 시절에 쓴 것이다** (2026-09-05에 31GB로 확인).
> 숫자 셋이 지금과 다르다 — Mongo 기본값은 약 7GB가 아니라 **약 15GB**를 노리고,
> 여유도 9GB가 아니다. 서비스도 다섯 개가 아니라 **여덟 개**로 늘었다.
> **묶는 것 자체는 그대로 두는 게 맞다** — 이유가 "안 그러면 스왑"에서
> **"측정을 반복해도 조건이 같아야 한다"**로 바뀔 뿐이다.
> 상한값을 지금 여유에 맞춰 다시 잡을지는 **미정**이다.

## 이 서버에서 같이 도는 것

`openclaw` 관련 컨테이너 셋(`openclaw-gateway`, `openclaw-cli`, `nginx-proxy`)은 **건드리지 않는다.**
`nginx-proxy`는 openclaw 게이트웨이(18789)를 443으로 노출하는 프록시라 openclaw의 일부다.

원래 돌던 모니터링 스택(`grafana`·`prometheus`·`alertmanager`·`node-exporter`·
`monitoring-postgres`·`postgres-exporter`)은 **포트 3000·9090이 겹쳐 멈춰뒀다.**
지운 게 아니라 멈춘 것이고 `restart` 정책만 껐으므로, 되살리려면:

```bash
ssh home1 'docker update --restart=always grafana prometheus alertmanager node-exporter \
  monitoring-postgres postgres-exporter && docker start grafana prometheus alertmanager \
  node-exporter monitoring-postgres postgres-exporter'
```

## Redis Sentinel — 장애 전환을 볼 때만 얹는다

**기본은 단일 Redis다.** 잔액 분산 락(Redisson)은 Redis에 닿지 못하면 행 락만으로 진행하고(폴백),
게이트웨이 요청 제한은 Redis가 없으면 통과시킨다(fail-open). 둘 다 Redis 없이 맞게 돌므로 노드 다섯을
상시로 두지 않는다. Sentinel은 **장애 전환을 직접 보고 싶을 때만** 아래처럼 얹는다 (Phase 6.7, `DECISIONS.md` D-006).

```bash
ssh home1 'cd ~/remittance && docker compose -f docker-compose.dev.yml -f docker-compose.homelab.yml \
  -f docker-compose.sentinel.yml up -d'
ssh home1 'cd ~/remittance && REDIS_SENTINEL=1 CPUSET=0-9 ./scripts/homelab-services.sh restart'
```

| | 주소 |
|---|---|
| 주 노드 (처음) | `127.0.0.1:6379` |
| 복제본 | `127.0.0.1:6380` |
| Sentinel × 3 | `127.0.0.1:26379` · `26380` · `26381` — master 이름 `remittance`, 정족수 2 |

**전부 호스트 네트워크다.** Sentinel이 클라이언트에게 알려주는 주 노드 주소가 `127.0.0.1`이어야
`--network host`로 뜬 서비스가 그대로 닿는다. 브리지 네트워크면 컨테이너 IP를 알려줘서 닿지 못한다.

**`REDIS_SENTINEL=1`을 빼먹지 않는다.** 빼면 서비스는 단일 모드로 `6379`에 직접 붙는다 —
평소엔 똑같이 돌다가 장애 전환 순간에만 옛 주 노드를 붙든 채 멈춘다. 틀려도 평소에는 증상이 없다.

지금 누가 주 노드인가:

```bash
ssh home1 'docker exec remittance-redis-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name remittance'
```

> Sentinel은 뜰 때마다 설정을 새로 만든다. 재기동하면 처음 토폴로지(6379가 주 노드)로 돌아가므로,
> 장애 전환을 시험한 뒤에는 **Sentinel까지 다시 올려** 출발선을 맞춘다.

### 장애 시험 — 둘로 나눠 본다

**1은 기본 구성에서 하고, 2만 Sentinel 오버레이가 필요하다.** 폴백은 Redis가 없을 때를 받고, Sentinel은
주 노드 장애를 몇 초로 줄인다 (D-006). 둘 다 부하를 건 채로 한다 — 조용할 때 죽이면 볼 것이 없다.

```bash
# 1. 기본 구성(단일 Redis) — Redis를 죽인다. 행 락만으로 계속 도는가
ssh home1 'docker stop remittance-redis'

# 2. Sentinel 오버레이 — 주 노드만 죽인다. Sentinel이 6380을 올리는가, 클라이언트가 따라가는가
ssh home1 'docker stop remittance-redis'
```

| 볼 것 | 1 (Redis 없음 · 기본) | 2 (주 노드만 · Sentinel) |
|---|---|---|
| 송금이 계속 종결되는가 | 끝까지 폴백으로 | 전환하는 몇 초 동안만 폴백, 이후 새 주 노드 |
| `remittance_balance_lock_fallback_total` | 계속 오른다 | 전환 구간에만 오른다 |
| `remittance_lock_unavailable_total{reason="circuit_open"}` | 계속 (5초마다 한 건씩 확인) | 잠깐 |
| 드레인 뒤 미종결 · 대사 불일치 | **0** | **0** |
| account 풀 pending | 줄 세우기가 빠진 만큼 오를 수 있다 — 여기가 폴백의 대가다 | 0 근처 |

1은 `docker start remittance-redis`로 되살린다. 2를 한 뒤에는 Sentinel까지 내렸다 올려 출발선을 맞춘다(위 경고).

**1의 결과 (2026-09-14)** — `measure-hot-account.sh 40 8`을 돌리는 동안 시작 34초에 끄고 96초에 켰다.
종결 p99가 기준선과 같았고(2,047 vs 2,048ms) 대사 0 · 풀 pending 0이었다. 대가는 active 8 → 13 · 행 락 대기
약 5배로 나타났다. 자세한 것은 `PROGRESS.md` "Redis 장애 시험 1".

> ⚠️ **장애 중 서비스가 살아 있는지는 `/actuator/health/readiness`로 본다.** 전체 health는 Redis가 꺼지면
> 1초 남짓에 DOWN(503)을 낸다 — 명령 타임아웃(1초)을 넣기 전에는 60초 뒤에야 답했다(2026-09-14).
> Redis 쪽은 `remittance_lock_unavailable_total`로 본다. 게이트웨이를 지나는 요청은 Redis가 죽어 있는 동안
> 하나하나 1초씩 늦는다 — 게이트웨이 경유 부하로 재면 그만큼을 감안한다.

## 정리

```bash
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh stop'
ssh home1 'cd ~/remittance && docker compose -f docker-compose.dev.yml -f docker-compose.homelab.yml down'
# Sentinel로 띄웠다면 그 파일까지 같이 준다. 빼면 복제본과 Sentinel이 남는다
ssh home1 'cd ~/remittance && docker compose -f docker-compose.dev.yml -f docker-compose.homelab.yml \
  -f docker-compose.sentinel.yml down'
# 측정을 새 출발선에서 하려면 볼륨까지
ssh home1 'docker volume rm remittance_remittance-mysql-data remittance_remittance-mongo-data'
```
