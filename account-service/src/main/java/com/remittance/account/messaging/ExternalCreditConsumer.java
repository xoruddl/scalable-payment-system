package com.remittance.account.messaging;

import com.remittance.account.saga.TransferSagaService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 상대 은행으로 나가는 입금만 받는다 — <b>별도 컨슈머 그룹, 별도 스레드</b> (Phase 6.5).
 *
 * <h2>왜 나눴나 — 숫자가 시켰다</h2>
 * 같은 리스너에서 내부 입금과 외부 호출을 다 하니 <b>느린 상대가 우리 내부 송금을 묶었다.</b>
 *
 * | 상대 2초 지연 | 내부 송금 종결 p99 |
 * |---|---|
 * | 한 리스너 | 58,790ms |
 * | 한 리스너 + 격벽 | 11,579ms |
 * | <b>리스너 분리</b> | <b>← 이 커밋이 재려는 값</b> |
 *
 * <p>격벽은 <b>같은 풀을 나눠 쓰면서 덜 뺏기는</b> 방법이다. 거기까지가 한계였다 —
 * 거절이 통과보다 많아지고(1,662 대 1,273) 그 거절을 처리하는 일이 다시 비용이 됐다.
 * <b>나눠 쓰지 않으면 애초에 뺏기지 않는다.</b>
 *
 * <h2>토픽은 같고 그룹만 다르다</h2>
 * 새 토픽을 만들지 않았다. 그러면 발행하는 쪽과 {@code transfer-service}까지 손봐야 한다.
 * 대신 <b>같은 토픽을 두 그룹이 각자 읽고 자기 몫만 처리</b>한다.
 * 메시지를 두 번 읽는 것이 대가인데, 남의 몫은 JSON 한 번 읽고 버리는 것뿐이라 싸다.
 *
 * <h2>⚠️ 처음 뜰 때 한 번 밀린 것을 다 읽는다</h2>
 * 새 그룹이라 {@code auto-offset-reset: earliest}로 <b>토픽 처음부터</b> 읽는다.
 * 지금 쌓인 50만 건을 훑고 지나간다 — 대부분 내부 송금이라 걸러지지만 시간은 걸린다.
 * {@code latest}로 두면 배포와 기동 사이에 발행된 <b>외부 송금이 통째로 사라지므로</b>
 * 그쪽이 훨씬 나쁘다. 처리 흔적이 있어 두 번 처리되지는 않는다.
 */
@Component
@RequiredArgsConstructor
public class ExternalCreditConsumer {

	/**
	 * 외부 호출 전용 스레드 수. <b>이 값이 곧 "상대에게 동시에 몇 개까지 보내나"</b>이고,
	 * 동시에 <b>이 서비스가 외부 때문에 잃을 수 있는 최대 스레드 수</b>다.
	 * 내부 리스너와 다른 풀이라 여기서 얼마를 잃든 내부 송금은 영향받지 않는다.
	 */
	private static final String CONCURRENCY = "${remittance.external-bank.listener.concurrency:6}";

	/** 내부 리스너와 <b>다른 그룹</b>이라야 각자 자기 스레드를 갖는다. */
	private static final String GROUP = "${spring.kafka.consumer.group-id}-external";

	private final TransferSagaService transferSagaService;
	private final ObjectMapper objectMapper;

	@KafkaListener(id = TransferEvents.DEBITED + ".external", topics = TransferEvents.DEBITED,
			groupId = GROUP, concurrency = CONCURRENCY)
	public void onDebited(String payload) {
		TransferEvents.Debited event = objectMapper.readValue(payload, TransferEvents.Debited.class);
		if (!event.isExternal()) {
			// 내부 송금은 남의 몫이다. 읽고 버린다 — 그게 이 방식의 대가다.
			return;
		}
		transferSagaService.onDebited(event);
	}
}
