package com.remittance.account.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 발행이 끝난 Outbox 행을 <b>보관 기간이 지나면 지운다</b>.
 *
 * <h2>왜 필요한가 — 2026-08-29에 실제로 당했다</h2>
 * 릴레이는 미발행 건만 읽고 나머지는 {@code publishedAt}만 찍는다. 그래서 발행이 끝난 행이
 * <b>영원히 남는다.</b> 사흘 측정하는 동안 이렇게 됐다.
 *
 * <pre>
 * account_db.outbox_events    2,406,230건  1,105 MB   (미발행 0건)
 * transfer_db.outbox_events   1,200,546건    551 MB   (미발행 0건)
 *
 * InnoDB 버퍼 풀 1,024 MB  vs  전체 데이터 2,405 MB   ← 69%가 이미 쓸모없는 행
 * </pre>
 *
 * <p>같은 100 TPS에서 종결 p99가 <b>4,673ms → 5,044ms</b>로 SLO를 넘었다.
 * 코드는 그대로였고 <b>쌓인 것 말고 바뀐 게 없었다.</b>
 *
 * <h2>보관 기간은 어디서 나오나</h2>
 * 발행이 끝난 행이 쓸모 있는 경우는 하나다 — 사고를 조사할 때 <b>"그 이벤트가 실제로
 * 발행됐나"</b>를 확인하는 것. 그러니 <b>사고가 가장 늦게 발견되는 시점</b>보다 길면 된다.
 *
 * <table>
 *   <tr><td>DLT 적재</td><td>즉시 (메트릭·WARN)</td></tr>
 *   <tr><td>미종결 송금</td><td>2분 (대사)</td></tr>
 *   <tr><td>모르는 돈</td><td>5분 (대사)</td></tr>
 *   <tr><td><b>전체 잔액 대사</b></td><td>EOD 배치로 갈 예정 — <b>하루</b></td></tr>
 * </table>
 *
 * <p>가장 늦은 것이 하루이므로, 사람이 조사할 시간을 더해 <b>3일</b>로 잡았다.
 * 숫자를 늘리고 싶으면 그 이유가 위 표에 있어야 한다.
 *
 * <h2>지우는 것도 부하다</h2>
 * 2026-08-29에 360만 건을 한 번에 지우고 <b>곧바로</b> 재봤더니 종결 p99가 11,768ms로
 * 오히려 두 배 나빠졌다. InnoDB가 뒤에서 삭제 흔적을 치우는 중이었기 때문이다
 * (`History list length`). 그래서 <b>작게 끊어서</b> 지우고, 측정 중에는 끌 수 있게 둔다 —
 * 대사 스케줄러가 측정을 오염시켰던 것과 같은 교훈이다.
 */
@Component
@ConditionalOnProperty(name = "outbox.retention.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRetention {

	private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

	/**
	 * 삭제는 <b>별도 빈</b>이 한다. 청크 하나가 트랜잭션 하나여야 하는데, 같은 빈 안에서
	 * 자기 메서드를 부르면 {@code @Transactional} 프록시를 타지 않는다 —
	 * {@link OutboxRelay}가 {@code OutboxBatchPublisher}를 따로 두는 것과 같은 이유다.
	 */
	private final OutboxChunkDeleter chunkDeleter;
	private final OutboxEventRepository repository;
	private final MeterRegistry meterRegistry;

	/** 발행 뒤 이만큼 지나면 지운다. 근거는 클래스 주석의 표. */
	@Value("${outbox.retention.keep-for:3d}")
	private Duration keepFor;

	/**
	 * 한 번에 지울 건수. 작게 잡는다 — 한 트랜잭션이 길어지면 그만큼 락을 오래 쥐고,
	 * 삭제 흔적도 한꺼번에 쏟아져 뒤에서 치우는 일이 커진다.
	 */
	@Value("${outbox.retention.chunk-size:1000}")
	private int chunkSize;

	/** 한 주기에 이어서 지울 최대 청크 수. 스케줄러 스레드를 무한히 붙들지 않기 위한 상한이다. */
	@Value("${outbox.retention.max-chunks-per-tick:5}")
	private int maxChunksPerTick;

	@Scheduled(fixedDelayString = "${outbox.retention.interval-ms:60000}")
	public void sweep() {
		int total = 0;
		for (int i = 0; i < maxChunksPerTick; i++) {
			int deleted = chunkDeleter.deleteChunk(
					com.remittance.account.support.Timestamps.now().minus(keepFor), chunkSize);
			total += deleted;
			// 덜 찼다 = 더 지울 게 없다. 다음 주기에 다시 본다.
			if (deleted < chunkSize) {
				break;
			}
		}
		if (total > 0) {
			deleted().increment(total);
			log.info("보관 기간이 지난 Outbox 행을 지웠다 ({}건, 보관 {})", total, keepFor);
		}
	}

	/**
	 * <b>발행이 끝났는데 아직 남아 있는 건수.</b>
	 *
	 * <p>이 값이 없어서 못 봤다. 미발행 적체(`remittance.outbox.backlog`)는 처음부터 보고 있었는데,
	 * <b>발행이 끝난 뒤 쌓이는 것은 아무도 세지 않았다.</b> 그래서 240만 건이 될 때까지 몰랐다.
	 */
	@PostConstruct
	void 남아있는_건수를_지표로_낸다() {
		Gauge.builder("remittance.outbox.retained", repository,
						OutboxEventRepository::countByPublishedAtIsNotNull)
				.description("발행이 끝났지만 보관 기간이 남아 아직 지우지 않은 Outbox 행 수")
				.register(meterRegistry);
		deleted();
	}

	private Counter deleted() {
		return Counter.builder("remittance.outbox.retention.deleted")
				.description("보관 기간이 지나 지운 Outbox 행 수")
				.register(meterRegistry);
	}
}
