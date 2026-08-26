#!/usr/bin/env bash
#
# 핫 계좌를 한 번 재고, 다음 측정이 오염되지 않게 큐가 빌 때까지 기다린다.
#
#   ./scripts/measure-hot-account.sh <도착률> <조각수>
#   ./scripts/measure-hot-account.sh 60 8
#
# 왜 스크립트로 굳혔나 — 손으로 돌리다 두 번 틀렸다.
#
#   ① 앞 실행의 적체가 다음 숫자를 오염시킨다.
#      k6가 끝나도 큐에는 아직 남아 있다. 바로 다음을 돌리면 그 대기가 새 실행의 지연으로 잡힌다.
#      그래서 여기서 미종결이 0이 될 때까지 기다린다.
#
#   ② 재기동 직후 첫 실행은 버려야 한다.
#      2026-08-24에 재기동 직후 잰 1조각 값(p95 32.0초)을 데워진 8조각 값(2.1초)과 비교해
#      "15.4배 빨라졌다"고 적을 뻔했다. 다시 재니 2.1초로 같았다.
#      JVM·커넥션 풀·컨슈머 리밸런스가 안 풀린 상태였다.
#      <b>A/B는 반드시 같은 온도에서 잰다.</b>
#
# 서버 안에서 돌린다 (집 밖에서 잴 때의 규칙, docs/HOMELAB.md).
# 서비스는 0~9번 코어, k6는 10~11번 코어:
#
#   CPUSET=0-9 ./scripts/homelab-services.sh restart
set -uo pipefail

cd "$(dirname "$0")/.."

RATE="${1:?도착률(TPS)을 넘겨라}"
SHARDS="${2:-1}"
DRAIN_TIMEOUT_TICKS="${DRAIN_TIMEOUT_TICKS:-60}"

# 재기 전에 <b>DB가 저장소와 같은 설정인지</b> 본다.
#
# 2026-08-24에 실험용 binlog_group_commit_sync_delay=1000을 켜둔 채 되돌리지 않았고,
# 그 위에서 잰 값을 결과로 적었다. 앱 커밋 해시는 확인했는데 DB 설정은 아무도 확인하지 않았다.
# 커밋 해시가 맞아도 <b>DB가 다르면 다른 시스템을 잰 것</b>이다.
#
# 여기 적힌 값이 docker-compose로 띄운 MySQL 8의 기본값이자 이 저장소가 전제하는 설정이다.
# 일부러 바꿔서 재는 실험이라면 EXPECT_DEFAULT_DB=0으로 끄면 된다.
check_db_settings() {
	[ "${EXPECT_DEFAULT_DB:-1}" = "1" ] || return 0
	local actual expected="0|1|1"
	actual="$(docker exec remittance-mysql mysql -uroot -proot -N -e "
		SELECT CONCAT(@@binlog_group_commit_sync_delay, '|',
		              @@innodb_flush_log_at_trx_commit, '|', @@sync_binlog);" 2>/dev/null)"
	if [ "$actual" != "$expected" ]; then
		echo "⚠️  MySQL 설정이 저장소 기준과 다르다 (delay|flush_log|sync_binlog)"
		echo "    기대: $expected"
		echo "    실제: $actual"
		echo "    이 상태로 잰 값은 다른 시스템의 값이다. 되돌리고 다시 재라:"
		echo "      docker exec remittance-mysql mysql -uroot -proot -e \\"
		echo "        \"SET GLOBAL binlog_group_commit_sync_delay=0;\""
		exit 2
	fi
}
check_db_settings

echo "######## RATE=$RATE SHARDS=$SHARDS ########"
docker run --rm --network host --cpuset-cpus="${K6_CPUSET:-10-11}" \
	-e "RATE=$RATE" -e "SHARDS=$SHARDS" \
	-v "$PWD:/work" -w /work grafana/k6:latest \
	run load-test/scenarios/hot-account.js 2>&1 |
	grep -E '^  === |종결|p95|p99|성공률|시간초과|처리량|미발사'

echo "   -- ① 적체가 끝까지 빠지는가 (과부하 회복) --"
#
# docs/SLO.md가 정한 용량 판정 절차다. p99가 좋아도 여기서 걸리면 그 TPS는 탈락이다.
#
#   부하 → 요청 중단 → Kafka·Outbox drain → cutoff 확정 → 정식 대사 1회 → 0건 확인
#
# 손으로 하면 빠뜨린다. 실제로 "과부하 회복"은 종료 조건에 적어두고도 한 번도 재지 않았다.
unsettled=""; lag=""; outbox=""
for _ in $(seq 1 "$DRAIN_TIMEOUT_TICKS"); do
	unsettled="$(docker exec remittance-mysql mysql -uroot -proot -N -e \
		"SELECT COUNT(*) FROM transfer_db.transfers WHERE status NOT IN ('COMPLETED','FAILED');" 2>/dev/null)"
	outbox="$(docker exec remittance-mysql mysql -uroot -proot -N -e \
		"SELECT (SELECT COUNT(*) FROM transfer_db.outbox_events WHERE published_at IS NULL)
		      + (SELECT COUNT(*) FROM account_db.outbox_events  WHERE published_at IS NULL);" 2>/dev/null)"
	lag=0
	for g in transfer-service account-service ledger-service notification-service; do
		n="$(docker exec remittance-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
			--bootstrap-server localhost:9092 --describe --group "$g" 2>/dev/null |
			awk 'NR>1 && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}')"
		lag=$((lag + n))
	done
	if [ "${unsettled:-1}" = "0" ] && [ "${outbox:-1}" = "0" ] && [ "$lag" = "0" ]; then
		echo "   드레인 완료 (미종결 0 · Outbox 0 · Kafka lag 0)"
		break
	fi
	sleep 10
done
if [ "${unsettled:-1}" != "0" ] || [ "${outbox:-1}" != "0" ] || [ "${lag:-1}" != "0" ]; then
	# 안 빠지면 알려야 한다. 조용히 넘어가면 다음 측정이 오염되고, 무엇보다
	# "새 요청을 끊으면 적체가 끝까지 빠진다"는 종료 조건을 못 지킨 것이다.
	echo "   ❌ 적체가 안 빠졌다 (미종결 ${unsettled:-?} · Outbox ${outbox:-?} · lag ${lag:-?})"
	echo "      이 TPS는 용량이 아니다. 이 상태로 다음을 재지 마라."
	exit 3
fi

echo "   -- ② 정식 대사 (drain 뒤 1회) --"
# 부하 중 주기 대사는 진행 중인 정상 Saga를 순간적인 불일치로 잡는다.
# 판정은 반드시 <b>드레인이 끝난 뒤</b> 한 번 돌린 결과로 한다 (docs/SLO.md).
verdict="$(curl -s -X POST localhost:8084/reconciliations/runs |
	python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('ERR|대사 응답을 읽지 못했다'); raise SystemExit
n = d.get('findingCount', d.get('finding_count'))
print('%s|계좌 %s건 대조, 어긋남 %s건' % ('OK' if n == 0 else 'NG', d.get('accountsChecked', '?'), n))
" 2>/dev/null)"
echo "      ${verdict#*|}"
case "$verdict" in
	OK*) echo "   ✅ 이 TPS는 용량 조건을 통과했다" ;;
	*)   echo "   ❌ 잔액–원장이 어긋났다. p99가 좋아도 이 TPS는 용량이 아니다"
	     echo "      자세히: curl -s localhost:8084/reconciliations/findings | head"
	     exit 4 ;;
esac
