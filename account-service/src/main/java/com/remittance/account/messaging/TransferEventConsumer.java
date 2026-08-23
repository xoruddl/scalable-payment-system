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
 * <p>컨슈머 그룹은 서비스 단위로 하나다. 인스턴스를 늘리면 파티션이 나뉘어 자동으로 분산되고,
 * 같은 송금의 이벤트는 파티션 키(송금 ID) 덕분에 항상 같은 인스턴스가 순서대로 받는다.
 *
 * <h2>스레드를 파티션 수에 맞춘다 (Phase 6 Step 2)</h2>
 * 기본값은 <b>리스너당 스레드 1개</b>다. 토픽을 파티션 3개로 만들어 뒀는데 정작 한 스레드가
 * 셋을 순차로 도니, 파티션을 나눈 의미가 없었다. 2026-08-22 측정에서 릴레이를 뚫자
 * {@code transfer.requested}에 <b>lag 13,800</b>이 쌓였고 리스너 처리량이 초당 52건에서 멈췄다.
 *
 * <p>스레드 수는 <b>파티션 수를 넘을 수 없다.</b> 넘겨 봐야 남는 스레드는 할당받을 파티션이 없어
 * 놀기만 한다. 그래서 파티션과 같은 3이 지금 올릴 수 있는 최댓값이고, 더 필요하면
 * 파티션부터 늘려야 한다.
 *
 * <h2>순서는 왜 안 깨지나</h2>
 * 스레드 하나가 파티션 하나를 통째로 맡는다. 같은 송금의 이벤트는 키가 같아 <b>같은 파티션</b>으로
 * 가므로, 여전히 <b>한 스레드가 순서대로</b> 처리한다. 병렬이 되는 것은 <b>서로 다른 송금끼리</b>다.
 *
 * <p>대신 서로 다른 송금이 <b>같은 계좌</b>를 건드리면 이제 진짜로 동시에 부딪친다.
 * 그건 분산 락과 낙관적 락이 막도록 이미 만들어 둔 부분이고, 이 변경의 목적 중 하나가
 * <b>부하를 거기까지 닿게 하는 것</b>이다.
 *
 * <h2>왜 yml이 아니라 여기인가</h2>
 * {@code spring.kafka.listener.concurrency}로 줘도 되지만, {@code src/test/resources/application.yml}이
 * 운영 설정을 통째로 가려서 <b>테스트는 스레드 1개로 돌게 된다.</b> 그러면 이 변경의 진짜 위험
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
	private static final String CONCURRENCY = "${remittance.kafka.listener.concurrency:3}";

	private final TransferSagaService transferSagaService;
	private final ObjectMapper objectMapper;

	@KafkaListener(id = TransferEvents.REQUESTED, topics = TransferEvents.REQUESTED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onRequested(String payload) {
		transferSagaService.onRequested(objectMapper.readValue(payload, TransferEvents.Requested.class));
	}

	@KafkaListener(id = TransferEvents.DEBITED, topics = TransferEvents.DEBITED, groupId = "${spring.kafka.consumer.group-id}",
			concurrency = CONCURRENCY)
	public void onDebited(String payload) {
		transferSagaService.onDebited(objectMapper.readValue(payload, TransferEvents.Debited.class));
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
