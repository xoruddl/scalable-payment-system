#!/usr/bin/env bash
#
# 홈서버에서 다섯 서비스를 띄우고 내린다.
#
#   ./scripts/homelab-services.sh build     # 컨테이너 안에서 jar를 굽는다
#   ./scripts/homelab-services.sh start     # JRE 컨테이너로 다섯 개를 띄운다
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
#   1/4이라, 다섯 개가 각자 3GB 넘게 잡으려 든다. 합치면 스왑으로 떨어지고,
#   **성능을 재려는 머신에서 스왑은 측정 실패다.**

set -euo pipefail

cd "$(dirname "$0")/.."

JRE_IMAGE="${JRE_IMAGE:-eclipse-temurin:21-jre}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
GRADLE_CACHE="${GRADLE_CACHE:-remittance-gradle-cache}"

# 서비스 이름:포트:최대 힙:컨테이너 메모리 한도
#
# account와 transfer가 부하를 가장 많이 받는다(잔액 변경과 접수). 나머지 셋은 가볍다.
# 컨테이너 한도는 힙보다 넉넉해야 한다 — 메타스페이스·스레드 스택·다이렉트 버퍼가 힙 바깥이다.
SERVICES=(
	"account:8081:1g:1500m"
	"transfer:8082:1g:1500m"
	"ledger:8083:512m:900m"
	"reconciliation:8084:512m:900m"
	"notification:8085:512m:900m"
)

# 부하 생성기를 이 머신에서 함께 돌릴 때만 쓴다(예: CPUSET=0-9).
# 비워두면 서비스가 코어를 전부 쓴다 — k6를 노트북에서 돌리는 기본 구성이 그렇다.
CPUSET="${CPUSET:-}"

container_name() { echo "remittance-$1-service"; }
jar_path() { echo "/app/$1-service/build/libs/$1-service-0.0.1-SNAPSHOT.jar"; }

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
		:reconciliation-service:bootJar :notification-service:bootJar
	echo "▶ 빌드된 jar"
	ls -lh ./*-service/build/libs/*.jar | awk '{print "   ", $9, $5}'
}

cmd_start() {
	for entry in "${SERVICES[@]}"; do
		IFS=: read -r name port heap mem <<<"$entry"
		local_name="$(container_name "$name")"
		docker rm -f "$local_name" >/dev/null 2>&1 || true

		local cpuset_arg=()
		[ -n "$CPUSET" ] && cpuset_arg=(--cpuset-cpus "$CPUSET")

		docker run -d \
			--name "$local_name" \
			--network host \
			--memory "$mem" \
			"${cpuset_arg[@]}" \
			-v "$PWD":/app:ro \
			-w /app \
			--restart no \
			"$JRE_IMAGE" \
			java "-Xmx$heap" "-Xms$heap" \
			-XX:+ExitOnOutOfMemoryError \
			-jar "$(jar_path "$name")" >/dev/null
		echo "▶ $local_name (포트 $port, 힙 $heap, 한도 $mem)"
	done

	echo "▶ 기동을 기다린다"
	for _ in $(seq 1 90); do
		local up=0
		for entry in "${SERVICES[@]}"; do
			IFS=: read -r _ port _ _ <<<"$entry"
			curl -s -m 1 "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && up=$((up + 1))
		done
		[ "$up" -eq "${#SERVICES[@]}" ] && { echo "   다섯 개 전부 UP"; break; }
		sleep 2
	done
	cmd_status
}

cmd_stop() {
	for entry in "${SERVICES[@]}"; do
		IFS=: read -r name _ _ _ <<<"$entry"
		docker rm -f "$(container_name "$name")" >/dev/null 2>&1 || true
	done
	echo "▶ 다섯 서비스를 내렸다"
}

# 떠 있는 것이 "내가 방금 만든 것"인지 확인한다.
#
# 2026-08-22 baseline에서 낡은 jar로 측정하고 전부 버린 적이 있다. build-info를 심어둔
# 이유가 정확히 이것이므로, 측정 전에 이 확인을 먼저 한다.
cmd_status() {
	local head; head="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
	local mismatch=0
	echo "▶ HEAD=$head"
	for entry in "${SERVICES[@]}"; do
		IFS=: read -r name port _ _ <<<"$entry"
		local commit
		commit="$(curl -s -m 2 "http://localhost:$port/actuator/info" 2>/dev/null \
			| sed -n 's/.*"commit":"\([^"]*\)".*/\1/p')"
		if [ "$commit" = "$head" ]; then
			printf "   %-14s %s ✅\n" "$name" "$commit"
		else
			printf "   %-14s %s 🔴 HEAD와 다르다\n" "$name" "${commit:-응답없음}"
			mismatch=1
		fi
	done
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
