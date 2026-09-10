#!/usr/bin/env bash
#
# 홈서버에서 여섯 서비스를 띄우고 내린다.
#
#   ./scripts/homelab-services.sh build     # 컨테이너 안에서 jar를 굽는다
#   ./scripts/homelab-services.sh start     # JRE 컨테이너로 여섯 개를 띄운다
#   ./scripts/homelab-services.sh status    # 떠 있는 것이 지금 커밋인지까지 확인한다
#   ./scripts/homelab-services.sh stop
#   ./scripts/homelab-services.sh restart
#
# 왜 호스트에 JDK를 깔지 않는가
#   깔아도 되지만 sudo가 필요하고, 이 머신은 측정용이라 상태를 단순하게 두는 편이 낫다.
#   빌드는 JDK 컨테이너, 실행은 JRE 컨테이너로 한다. Phase 7에서 서비스마다 제대로 된
#   Dockerfile을 쓸 때까지의 임시 방편이다 — 여기서는 이미지를 만들지 않고 jar만 마운트한다.
#
# 왜 --network host 인가
#   노트북에서 `java -jar`로 띄우던 것과 같은 그림을 유지하기 위해서다. 서비스가 호스트
#   포트에 그대로 붙으므로 MySQL·Kafka 주소(localhost:3306 …)도, Prometheus 수집 설정도
#   노트북과 똑같이 쓴다. 리눅스에서만 되는 방식이라 이 스크립트는 홈서버 전용이다.
#
# 왜 힙을 손으로 잡는가
#   이 머신은 CPU는 남고 메모리가 빠듯하다(15GB). JVM 기본 최대 힙은 사용 가능한 메모리의
#   1/4이라, 여섯 개가 각자 3GB 넘게 잡으려 든다. 합치면 스왑으로 떨어지고,
#   **성능을 재려는 머신에서 스왑은 측정 실패다.**

set -euo pipefail

cd "$(dirname "$0")/.."

JRE_IMAGE="${JRE_IMAGE:-eclipse-temurin:21-jre}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
GRADLE_CACHE="${GRADLE_CACHE:-remittance-gradle-cache}"

# <b>Gradle 모듈 이름</b>:포트:최대 힙:컨테이너 메모리 한도
#
# ⚠️ 예전에는 앞 칸이 "account" 같은 짧은 이름이었고 스크립트가 뒤에 `-service`를 붙였다.
# Phase 4에서 `gateway`가 들어오면서 그 가정이 깨졌다(모듈 이름에 `-service`가 없다).
# 그래서 <b>모듈 이름을 그대로</b> 적는다 — 컨테이너 이름과 jar 경로가 모듈에서 바로 나온다.
#
# account와 transfer가 부하를 가장 많이 받는다(잔액 변경과 접수). 나머지는 가볍다.
# 컨테이너 한도는 힙보다 넉넉해야 한다 — 메타스페이스·스레드 스택·다이렉트 버퍼가 힙 바깥이다.
SERVICES=(
	"account-service:8081:1g:1500m"
	"transfer-service:8082:1g:1500m"
	"ledger-service:8083:512m:900m"
	"reconciliation-service:8084:512m:900m"
	"notification-service:8085:512m:900m"
	# Phase 6.5 — 상대 은행(Kotlin). 일부러 느리게 답하는 일이 있어 스레드를 넉넉히 쓴다.
	"external-bank-service:8086:512m:900m"
	# Phase 4 — 단일 진입점. 요청을 뒤로 흘려보내기만 하므로 가볍다.
	"gateway:8080:512m:900m"
)

# 부하 생성기를 이 머신에서 함께 돌릴 때만 쓴다(예: CPUSET=0-9).
# 비워두면 서비스가 코어를 전부 쓴다 — k6를 노트북에서 돌리는 기본 구성이 그렇다.
CPUSET="${CPUSET:-}"

# 실험용 설정을 서비스에 넘긴다. 공백으로 구분한 KEY=VALUE 목록.
#
#   SERVICE_ENV="ACCOUNT_LOCK_STRATEGY=OPTIMISTIC" ./scripts/homelab-services.sh restart
#
# 왜 이렇게 하나: 같은 것을 두 가지 설정으로 비교하려면 **같은 jar**여야 한다.
# 코드를 고쳐가며 재면 빌드가 달라져 무엇 때문에 숫자가 바뀌었는지 말할 수 없다.
SERVICE_ENV="${SERVICE_ENV:-}"

# <b>2번째 인스턴스부터만</b> 추가로 넘기는 설정. SERVICE_ENV 뒤에 붙으므로 같은 키면 이긴다.
#
#   REPLICAS="account-service=2" SERVICE_ENV_2="OUTBOX_RELAY_ENABLED=false" \
#     ./scripts/homelab-services.sh restart
#
# 왜 필요한가: replica를 늘렸을 때 나빠지는 것이 <b>무엇 때문인지</b> 가르려면
# 인스턴스마다 다르게 켜봐야 한다. 2026-09-05에 account를 2대로 늘리니 종결 p99가
# 2,849 → 6,897ms가 됐는데, 락·낙관적 락·조각 선택·컨슈머 수를 전부 배제하고도
# 원인이 안 나왔다. 남은 후보가 "릴레이가 두 벌"이라 한쪽만 꺼봐야 한다.
SERVICE_ENV_2="${SERVICE_ENV_2:-}"

# 같은 서비스를 여러 벌 띄운다. 공백으로 구분한 모듈=개수 목록.
#
#   REPLICAS="transfer-service=2 account-service=2" ./scripts/homelab-services.sh restart
#
# 왜 필요한가: <b>인스턴스가 하나면 존재할 수 없는 결함</b>이 있다. Outbox 릴레이의 중복
# 발행이 그렇다 — 잠금 없이 미발행 행을 집으면 두 릴레이가 같은 100건을 둘 다 보낸다.
# 단위 테스트에서는 스레드 둘로 흉내 냈지만, 진짜 두 프로세스가 각자 커넥션 풀과
# 스케줄러를 들고 도는 것과는 다르다. Phase 8에서 replica를 올리기 전에 여기서 본다.
#
# --network host라 포트가 곧 주소다. 2번째부터는 +100 해서 충돌을 피한다(8082 → 8182).
# 게이트웨이는 기본 포트만 알므로 <b>추가 인스턴스는 HTTP를 받지 않는다</b> —
# Kafka 소비와 Outbox 릴레이에만 참여한다. 릴레이를 보려는 목적에는 그게 맞다.
REPLICAS="${REPLICAS:-}"

container_name() { echo "remittance-$1"; }
jar_path() { echo "/app/$1/build/libs/$1-0.0.1-SNAPSHOT.jar"; }

replica_count() {
	local kv
	for kv in $REPLICAS; do
		[ "${kv%%=*}" = "$1" ] && { echo "${kv#*=}"; return; }
	done
	echo 1
}

# SERVICES를 replica까지 펼친 목록.
# 한 줄에 "모듈 포트 힙 한도 컨테이너이름 <b>replica번호</b>".
# start·status가 모두 이걸 돌므로 두 곳이 어긋날 일이 없다.
expand_instances() {
	local entry name port heap mem n i
	for entry in "${SERVICES[@]}"; do
		IFS=: read -r name port heap mem <<<"$entry"
		n="$(replica_count "$name")"
		for i in $(seq 1 "$n"); do
			if [ "$i" -eq 1 ]; then
				echo "$name $port $heap $mem $(container_name "$name") 1"
			else
				echo "$name $((port + 100 * (i - 1))) $heap $mem $(container_name "$name")-$i $i"
			fi
		done
	done
}

cmd_build() {
	echo "▶ 컨테이너 안에서 빌드한다 (호스트에 JDK가 없어도 된다)"
	# 컨테이너 안에는 git이 없다. 커밋은 호스트에서 읽어 넘긴다 —
	# 안 그러면 build-info가 'unknown'이 되어 "떠 있는 게 어느 커밋이냐"에 답할 수 없다.
	local commit branch
	commit="$(git rev-parse --short=12 HEAD)"
	branch="$(git rev-parse --abbrev-ref HEAD)"
	echo "   커밋 $commit ($branch)를 빌드에 새긴다"

	docker run --rm \
		-v "$PWD":/work -w /work \
		-v "$GRADLE_CACHE":/root/.gradle \
		"$JDK_IMAGE" \
		./gradlew --no-daemon "-PgitCommit=$commit" "-PgitBranch=$branch" \
		:account-service:bootJar :transfer-service:bootJar :ledger-service:bootJar \
		:reconciliation-service:bootJar :notification-service:bootJar \
		:external-bank-service:bootJar :gateway:bootJar
	echo "▶ 빌드된 jar"
	ls -lh ./*-service/build/libs/*.jar ./gateway/build/libs/*.jar | awk '{print "   ", $9, $5}'
}

cmd_start() {
	local name port heap mem cname idx total=0
	while read -r name port heap mem cname idx; do
		docker rm -f "$cname" >/dev/null 2>&1 || true

		local cpuset_arg=()
		[ -n "$CPUSET" ] && cpuset_arg=(--cpuset-cpus "$CPUSET")

		# SERVICE_ENV_2는 뒤에 붙인다 — 같은 키면 나중 -e가 이기므로 2번째부터 덮어쓴다.
		local env_args=()
		for kv in $SERVICE_ENV; do env_args+=(-e "$kv"); done
		if [ "$idx" -gt 1 ]; then
			for kv in $SERVICE_ENV_2; do env_args+=(-e "$kv"); done
		fi

		docker run -d \
			--name "$cname" \
			--network host \
			--memory "$mem" \
			"${cpuset_arg[@]}" \
			"${env_args[@]}" \
			-e "SERVER_PORT=$port" \
			-v "$PWD":/app:ro \
			-w /app \
			--restart no \
			"$JRE_IMAGE" \
			java "-Xmx$heap" "-Xms$heap" \
			-XX:+ExitOnOutOfMemoryError \
			-jar "$(jar_path "$name")" >/dev/null
		echo "▶ $cname (포트 $port, 힙 $heap, 한도 $mem)"
		total=$((total + 1))
	done < <(expand_instances)

	echo "▶ 기동을 기다린다"
	for _ in $(seq 1 90); do
		local up=0
		while read -r _ port _ _ _ _; do
			curl -s -m 1 "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && up=$((up + 1))
		done < <(expand_instances)
		[ "$up" -eq "$total" ] && { echo "   ${total}개 전부 UP"; break; }
		sleep 2
	done
	cmd_status
}

# ⚠️ expand_instances로 내리면 안 된다. REPLICAS가 <b>지금</b> 안 켜져 있으면
# 지난번에 띄운 -2가 목록에 안 잡혀 <b>살아남는다.</b> 2026-09-05에 실제로 당했다 —
# REPLICAS 없이 restart하고 "1대로 되돌렸다"고 믿은 채 2대로 측정했고,
# 심지어 -2는 이전 SERVICE_ENV(concurrency=3)를 그대로 들고 있어 설정까지 섞였다.
# 그래서 <b>이름으로 훑어서</b> remittance-<모듈>과 remittance-<모듈>-N을 전부 내린다.
cmd_stop() {
	local entry name count=0 c
	for entry in "${SERVICES[@]}"; do
		IFS=: read -r name _ _ _ <<<"$entry"
		for c in $(docker ps -a --format '{{.Names}}' |
				grep -E "^$(container_name "$name")(-[0-9]+)?$" || true); do
			docker rm -f "$c" >/dev/null 2>&1 || true
			count=$((count + 1))
		done
	done
	echo "▶ ${count}개를 내렸다"
}

# 떠 있는 것이 "내가 방금 만든 것"인지 확인한다.
#
# 2026-08-22 baseline에서 낡은 jar로 측정하고 전부 버린 적이 있다. build-info를 심어둔
# 이유가 정확히 이것이므로, 측정 전에 이 확인을 먼저 한다.
cmd_status() {
	local head; head="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
	local mismatch=0
	echo "▶ HEAD=$head"
	local name port cname
	while read -r name port _ _ cname _; do
		local commit info extra
		# replica는 이름이 아니라 컨테이너 이름으로 구분해야 한다 —
		# 같은 모듈이 두 줄 나오면 어느 쪽이 빨간지 알 수 없다.
		[ "$cname" = "$(container_name "$name")" ] || name="$cname"
		info="$(curl -s -m 2 "http://localhost:$port/actuator/info" 2>/dev/null)"
		commit="$(echo "$info" | sed -n 's/.*"commit":"\([^"]*\)".*/\1/p')"
		# 실험 설정도 함께 보여준다. 어느 전략으로 쟀는지 물어볼 수 없으면
		# 나중에 그 숫자가 무엇이었는지 말할 수 없다 — 커밋을 확인하는 이유와 같다.
		extra=""
		case "$info" in
			*accountLockStrategy*)
				extra=" [$(echo "$info" | sed -n 's/.*"accountLockStrategy":"\([^"]*\)".*/\1/p')]" ;;
		esac
		if [ "$commit" = "$head" ]; then
			printf "   %-32s %s ✅%s\n" "$name" "$commit" "$extra"
		else
			printf "   %-32s %s 🔴 HEAD와 다르다\n" "$name" "${commit:-응답없음}"
			mismatch=1
		fi
	done < <(expand_instances)
	[ "$mismatch" -eq 0 ] || {
		echo "   ⚠️  이 상태로 측정하면 무엇을 쟀는지 알 수 없다. build 후 restart 하라."
		return 1
	}
}

case "${1:-}" in
	build) cmd_build ;;
	start) cmd_start ;;
	stop) cmd_stop ;;
	status) cmd_status ;;
	restart) cmd_stop; cmd_start ;;
	*)
		echo "사용법: $0 {build|start|stop|restart|status}" >&2
		exit 2
		;;
esac
