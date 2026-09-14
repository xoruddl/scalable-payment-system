package com.remittance.account.messaging;

import com.remittance.account.saga.TransferSagaService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Saga 이벤트 수신부. 역직렬화 외에는 아무 판단도 하지 않고 {@link TransferSagaService}에 넘긴다 —
 * 메시징 기술과 도메인 처리를 섞지 않기 위해서다.
 *
 * 컨슈머 그룹은 서비스 단위로 하나다. 인스턴스를 늘리면 파티션이 나뉘어 자동으로 분산되고,
 * 같은 송금의 이벤트는 파티션 키(송금 ID) 덕분에 항상 같은 인스턴스가 순서대로 받는다.
 *
 * 스레드를 파티션 수에 맞춘다 (Phase 6 Step 2)
 * 기본값은 리스너당 스레드 1개다. 토픽을 파티션 3개로 만들어 뒀는데 정작 한 스레드가
 * 셋을 순차로 도니, 파티션을 나눈 의미가 없었다. 2026-08-22 측정에서 릴레이를 뚫자
 * {@code transfer.requested}에 lag 13,800이 쌓였고 리스너 처리량이 초당 52건에서 멈췄다.
 *
 * 스레드 수는 파티션 수를 넘을 수 없다. 넘겨 봐야 남는 스레드는 할당받을 파티션이 없어
 * 놀기만 한다. 그래서 파티션과 같은 3이 지금 올릴 수 있는 최댓값이고, 더 필요하면
 * 파티션부터 늘려야 한다.
 *
 * 순서는 왜 안 깨지나
 * 스레드 하나가 파티션 하나를 통째로 맡는다. 같은 송금의 이벤트는 키가 같아 같은 파티션으로
 * 가므로, 여전히 한 스레드가 순서대로 처리한다. 병렬이 되는 것은 서로 다른 송금끼리다.
 *
 * 대신 서로 다른 송금이 같은 계좌를 건드리면 이제 진짜로 동시에 부딪친다.
 * 그건 잔액 락(Redis 락 · 행 락, {@code BalanceGuard})이 막도록 이미 만들어 둔 부분이고, 이 변경의 목적 중 하나가
 * 부하를 거기까지 닿게 하는 것이다.
 *
 * 왜 yml이 아니라 여기인가
 * {@code spring.kafka.listener.concurrency}로 줘도 되지만, {@code src/test/resources/application.yml}이
 * 운영 설정을 통째로 가려서 테스트는 스레드 1개로 돌게 된다. 그러면 이 변경의 진짜 위험
 * (동시 처리에서의 순서·멱등성)을 기존 테스트가 하나도 밟아 보지 못한다.
 * 같은 함정을 {@code MetricsDistributionConfig}에서 한 번 밟았으므로, 기본값을 코드에 둔다.
 * 운영에서 조정할 여지는 프로퍼티로 남겨 둔다.
 */
@Component
@RequiredArgsConstructor
public class TransferEventConsumer {

	/**
	 * 리스너당 스레드 수. 파티션 수(3)에 맞춘 값이 기본이고, 파티션을 넘겨 봐야 놀기만 한다.
	 * 프로퍼티로 낮출 수 있게 열어 두지만, 기본값은 코드에 있어야 테스트가 같은 값으로 돈다.
	 */
	private static final String CONCURRENCY = "${remittance.kafka.listener.concurrency:6}";

	private final TransferSagaService transferSagaService;
	private final ObjectMapper objectMapper;

	@KafkaListener(id = TransferEvents.REQUESTED, topics = TransferEvents.REQUESTED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onRequested(String payload) {
		transferSagaService.onRequested(objectMapper.readValue(payload, TransferEvents.Requested.class));
	}

	/**
	 * 입금 — 우리 은행 계좌로 가는 것만 처리한다 (Phase 6.5).
	 *
	 * 상대 은행으로 가는 것은 {@link ExternalCreditConsumer}가 별도 컨슈머 그룹으로
	 * 받는다. 같은 리스너에서 둘 다 처리했더니 느린 상대가 우리 내부 송금까지 묶었다 —
	 * 상대가 2초 느려지자 내부 송금 종결 p99가 3,071 → 58,790ms가 됐다.
	 *
	 * 격벽으로 11,579ms까지 줄였지만 같은 스레드 풀을 나눠 쓰는 한 거기까지였다.
	 * 나눠 쓰지 않으면 애초에 뺏기지 않는다.
	 */
	@KafkaListener(id = TransferEvents.DEBITED, topics = TransferEvents.DEBITED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onDebited(String payload) {
		TransferEvents.Debited event = objectMapper.readValue(payload, TransferEvents.Debited.class);
		if (event.isExternal()) {
			// 남의 몫이다. 여기서 건드리면 분리한 의미가 없다.
			return;
		}
		transferSagaService.onDebited(event);
	}

	/**
	 * 이 서비스가 발행한 이벤트를 이 서비스가 다시 받는다. 한 바퀴 도는 게 낭비처럼 보이지만,
	 * 그래야 환불이 실패했을 때 브로커가 다시 배달해준다 ({@link TransferEvents#CREDIT_FAILED} 참고).
	 */
	@KafkaListener(id = TransferEvents.CREDIT_FAILED, topics = TransferEvents.CREDIT_FAILED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onCreditFailed(String payload) {
		transferSagaService.onCreditFailed(objectMapper.readValue(payload, TransferEvents.CreditFailed.class));
	}
}
