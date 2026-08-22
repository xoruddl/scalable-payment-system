# 홈서버 — 측정 전용 환경

성능 숫자를 재는 곳입니다. 노트북은 개발과 부하 생성에 쓰고, **재는 대상은 여기에 둡니다.**

## 왜 옮겼나

노트북에서 재면 **부하 생성기와 측정 대상이 같은 CPU를 두고 싸웁니다.**
2026-08-22 baseline에서 그 대가를 치렀습니다 — 원장 조회 시나리오의 p99가 8초까지 갔는데
정작 `ledger` 프로세스의 CPU는 4.9%였고 호스트가 91%였습니다. **원장이 아니라 노트북을
재고 있었던 것**이라 그 시나리오는 baseline에서 통째로 빼야 했습니다.

k6를 `--cpus="4"`로 묶자 같은 부하에서 처리량이 211 → 346 req/s로 올랐습니다.
**부하를 거는 쪽을 굶겼더니 측정 대상이 그제야 일을 했다**는 뜻입니다.

## 이 머신

| | |
|---|---|
| CPU | AMD Ryzen 5 3600 — **6코어 / 12스레드** |
| 메모리 | 15GB |
| 디스크 | 98GB (여유 72GB) |
| OS | Ubuntu 24.04 LTS |
| 접속 | `ssh home1` (집 안) / `ssh home2` (집 밖) — 아래 "주소가 둘인 문제" 참고 |
| 작업 경로 | `~/remittance` |

**코어 수는 노트북과 같습니다.** 그러니 여기의 이점은 코어가 많아서가 아닙니다.

| 얻는 것 | |
|---|---|
| **전용** | IDE·브라우저가 없어 측정할 때마다 조건이 같습니다 |
| **리눅스** | `cpuset`이 실제로 듣고, Docker Desktop VM 오버헤드가 없습니다 |
| **시각 정밀도** | `Instant.now()`가 나노초까지 나와, macOS에서 재현 안 되던 버그를 로컬에서 잡습니다 (`AGENTS.md` 참고) |
| **상시 가동** | Phase 8의 ArgoCD처럼 계속 살아 있어야 의미 있는 것들을 올릴 수 있습니다 |
| **호스트 지표** | `node-exporter`가 붙어 있어 "앱이 느린가 머신이 느린가"를 화면에서 바로 가릅니다 |

## 부하를 어디서 거나

**기본은 서버 안에서 코어를 갈라 겁니다** (서비스 0~9번, k6 10~11번).

처음에는 노트북에서 거는 쪽을 기본으로 잡았는데, **노트북이 WiFi로 붙어 있어 RTT가
접수 지연에 그대로 더해집니다.** 그런데 그 접수 지연(커넥션 풀 고갈)이 baseline의 핵심
발견이라 오염시킬 수 없었습니다. 리눅스에서는 코어 고정이 실제로 들으므로, 한 머신
안에서도 자원 분리가 됩니다.

> k6에 코어를 2개 주든 4개 주든 결과가 같은 것을 확인했습니다(400.6 vs 402.8 req/s).
> 부하 생성기가 병목이 아니라는 뜻이라, 2개로 충분합니다.

노트북과 서버는 WiFi로 붙어 있는데, **정작 중요한 숫자는 영향을 받지 않습니다.**

| 측정 대상 | WiFi 영향 |
|---|---|
| 종결 처리량, Outbox 적체, 락 대기, 커넥션 풀 | **없음** — 접수된 뒤 서버 안에서만 도는 값입니다 |
| 접수 지연 p95/p99 | **있음** — RTT와 지터가 그대로 더해집니다 |

접수 지연을 정밀하게 봐야 할 때만 **서버 안에서** k6를 돌리고, 그때는 코어를 갈라 씁니다.

```bash
# 서버 안에서 돌릴 때: k6는 10~11번 코어, 서비스는 0~9번
ssh home1 'cd ~/remittance && CPUSET=0-9 ./scripts/homelab-services.sh restart'
ssh home1 'cd ~/remittance && docker run --rm -i --network host --cpuset-cpus="10-11" \
  -v "$PWD:/work" -w /work grafana/k6:latest run load-test/scenarios/spread.js'
```

## 절차

### 1. 소스를 보낸다

노트북에서 (아직 푸시하지 않은 커밋도 그대로 넘어갑니다):

```bash
rsync -az --delete --exclude 'build/' --exclude '.gradle/' --exclude '.idea/' \
  ./ home1:~/remittance/
```

### 2. 빌드 — 호스트에 JDK가 없다

이 머신에는 Java를 깔지 않았습니다. sudo가 필요하고, 측정용 머신은 상태가 단순한 편이
낫기 때문입니다. **빌드는 JDK 컨테이너 안에서** 합니다.

```bash
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh build'
```

> 컨테이너 안에는 `git`이 없어서 build-info의 커밋이 `unknown`으로 떨어집니다.
> 스크립트가 **호스트에서 커밋을 읽어 `-PgitCommit`으로 넘깁니다.**
> 이게 없으면 "지금 떠 있는 게 어느 커밋이냐"에 답할 수 없고, build-info를 심어둔
> 이유 자체가 사라집니다. Phase 7의 이미지 빌드에서도 같은 문제를 만납니다.

### 3. 인프라와 서비스를 띄운다

```bash
ssh home1 'cd ~/remittance && docker compose -f docker-compose.dev.yml -f docker-compose.homelab.yml up -d'
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh start'
```

서비스는 이미지를 만들지 않고 **JRE 컨테이너에 jar만 마운트**해 띄웁니다
(`--network host`라 노트북에서 `java -jar`로 띄우던 것과 같은 그림입니다).
Phase 7에서 서비스마다 제대로 된 Dockerfile을 쓸 때까지의 임시 방편입니다.

### 4. 측정 전에 반드시 — 떠 있는 게 내가 만든 것인가

```bash
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh status'
```

다섯 서비스의 `/actuator/info`가 HEAD와 같은지 확인합니다.
**2026-08-22 baseline 1차를 낡은 jar로 재고 전부 버린 적이 있습니다.** 그때 `/actuator/info`는
정직하게 옛 커밋을 답하고 있었는데 물어보지 않았을 뿐입니다.

### 5. 화면 — IP를 치지 말고 SSH 터널로 봅니다

```bash
ssh -N -L 3000:localhost:3000 -L 9090:localhost:9090 home1   # 집 안
ssh -N -L 3000:localhost:3000 -L 9090:localhost:9090 home2   # 집 밖
```

띄워둔 채 노트북에서 http://localhost:3000 (Grafana), http://localhost:9090 (Prometheus)을 엽니다.

**IP를 문서나 스크립트에 박지 않습니다.** 집 안(`192.168.55.x`)과 집 밖(공인 IP)에서
주소가 다르고, 공인 IP는 ISP가 바꿀 수 있습니다. `~/.ssh/config`의 별칭만 쓰면
주소가 바뀌어도 그 파일 한 곳만 고치면 됩니다.

> ⚠️ **이 포트들을 공유기에서 인터넷으로 포워딩하지 마세요.**
> 우리 Grafana는 `GF_AUTH_ANONYMOUS_ENABLED=true`에 **Admin 권한**으로 떠 있고,
> `/actuator`가 열려 있고, MySQL은 `root/root`입니다. 로컬 측정용이라 그렇게 둔 것이지
> 인터넷에 내놓을 수 있는 상태가 아닙니다. 터널은 이미 인증된 SSH를 타므로
> 아무것도 새로 열지 않습니다.

## 주소가 둘인 문제 — 집 밖에서는 부하를 서버 안에서 겁니다

이 머신은 집 안에서는 `192.168.55.167`, 집 밖에서는 공인 IP로 붙습니다.
`~/.ssh/config`에 `home1`(집 안)·`home2`(집 밖) 별칭이 있으므로 **접속과 조작은 그대로**입니다.
빌드·기동·측정을 전부 SSH로 하기 때문에 주소가 바뀌어도 절차가 같습니다.

**달라지는 건 부하를 어디서 거느냐 하나입니다.**

| 있는 곳 | 부하 생성기 | 이유 |
|---|---|---|
| 집 안 | **노트북** (기본) | 유선/무선이라도 LAN이라 지연이 짧고, 자원이 완전히 분리됩니다 |
| 집 밖 | **서버 안에서** `CPUSET`으로 분리 | 인터넷을 넘어 부하를 걸면 병목이 앱인지 회선인지 알 수 없습니다 |

```bash
# 집 밖에서 잴 때: 서비스는 0~9번 코어, k6는 10~11번 코어
ssh home2 'cd ~/remittance && CPUSET=0-9 ./scripts/homelab-services.sh restart'
ssh home2 'cd ~/remittance && docker run --rm -i --network host --cpuset-cpus="10-11" \
  -v "$PWD:/work" -w /work grafana/k6:latest run load-test/scenarios/spread.js'
```

> **8081~8085를 인터넷에 열어 노트북에서 때리는 방식은 쓰지 마세요.**
> 측정이 무의미해지고(RTT·업로드 대역폭이 그대로 섞임) 보안도 나쁩니다.
>
> 다만 **같은 시나리오라도 부하를 건 위치가 다르면 비교하면 안 됩니다.**
> 노트북에서 잰 값과 서버 안에서 잰 값은 접수 지연이 다릅니다
> (종결 처리량·적체·락 대기 같은 서버 내부 지표는 영향받지 않습니다).
> 측정 기록에 **어디서 걸었는지**를 함께 남기세요.

## 메모리를 손으로 묶는 이유

이 머신은 **CPU는 남고 메모리가 빠듯합니다.** 기본값대로 두면,

- MongoDB가 WiredTiger 캐시로 `(RAM-1GB)의 50%` = **약 7GB**를 가져갑니다
- JVM 다섯 개가 각자 사용 가능 메모리의 1/4을 힙 상한으로 잡습니다

합치면 물리 메모리를 넘겨 스왑으로 떨어집니다. **성능을 재려는 머신에서 스왑은 측정 실패입니다.**
그래서 `docker-compose.homelab.yml`과 `scripts/homelab-services.sh`가 전부 명시적으로 묶습니다
(합계 약 8GB, 실측 사용 5GB / 여유 9GB).

## 이 서버에서 같이 도는 것

`openclaw` 관련 컨테이너 셋(`openclaw-gateway`, `openclaw-cli`, `nginx-proxy`)은 **건드리지 않습니다.**
`nginx-proxy`는 openclaw 게이트웨이(18789)를 443으로 노출하는 프록시라 openclaw의 일부입니다.

원래 돌던 모니터링 스택(`grafana`·`prometheus`·`alertmanager`·`node-exporter`·
`monitoring-postgres`·`postgres-exporter`)은 **포트 3000·9090이 겹쳐 멈춰뒀습니다.**
지운 게 아니라 멈춘 것이고 `restart` 정책만 껐으므로, 되살리려면:

```bash
ssh home1 'docker update --restart=always grafana prometheus alertmanager node-exporter \
  monitoring-postgres postgres-exporter && docker start grafana prometheus alertmanager \
  node-exporter monitoring-postgres postgres-exporter'
```

## 정리

```bash
ssh home1 'cd ~/remittance && ./scripts/homelab-services.sh stop'
ssh home1 'cd ~/remittance && docker compose -f docker-compose.dev.yml -f docker-compose.homelab.yml down'
# 측정을 새 출발선에서 하려면 볼륨까지
ssh home1 'docker volume rm remittance_remittance-mysql-data remittance_remittance-mongo-data'
```
