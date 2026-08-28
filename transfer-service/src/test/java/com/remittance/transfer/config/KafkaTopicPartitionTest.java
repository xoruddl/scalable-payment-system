package com.remittance.transfer.config;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.messaging.TransferEvents;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 Step 4d — e2e에서 드러난 결함에 대한 회귀 테스트.
 *
 * <p>세 서비스를 실제로 띄운 e2e에서 드러난 문제다. 이 서비스는 <b>발행하는</b> 토픽만
 * {@code NewTopic}으로 선언하고, <b>소비하는</b> 토픽은 남이 만들어주기를 기대한다.
 * 그런데 컨슈머가 토픽이 생기기 전에 구독하면 <b>브로커가 기본값(1파티션)으로 자동 생성</b>해버린다.
 *
 * <p>그 뒤 발행하는 서비스가 3파티션으로 늘려도, 이미 붙은 컨슈머는 늘어난 파티션을 모른다
 * (기본 {@code metadata.max.age.ms}가 5분). e2e에서 실제로 벌어진 일이다 —
 * transfer-service가 {@code transfer.debited-0}만 할당받은 채, p1·p2에 떨어진 메시지를
 * 5분 동안 보지 못해 Saga 전체가 멈췄다.
 *
 * <pre>
 * transfer-service: partitions assigned: [transfer.debited-0]     ← p0만
 * account-service:  transfer.debited p0, p1, p2                   ← 전부
 * </pre>
 *
 * <p>유실은 아니고 메타데이터가 갱신되면 회복되지만, 컨테이너·K8s로 매번 새 환경을 띄우는
 * Phase 6·7에서는 콜드 스타트마다 겪게 된다.
 */
@SpringBootTest
class KafkaTopicPartitionTest extends AbstractIntegrationTest {

	/**
	 * 파티션 수는 순서 보장의 전제다. 한 번 잘못 만들어지면 나중에 줄일 수도 없다.
	 *
	 * <p>기대값을 여기 적지 않고 <b>운영 설정에서 그대로 읽는다.</b> 따로 적어두면
	 * 같은 숫자가 두 곳에 있게 되고, 바꿀 때 한쪽만 고쳐 red를 보게 된다.
	 * 이 테스트가 볼 것은 "3인가"가 아니라 <b>"선언한 대로 만들어졌는가"</b>다.
	 */
	private static final int EXPECTED_PARTITIONS = KafkaTopicsConfig.PARTITIONS;

	@Autowired
	private KafkaAdmin kafkaAdmin;

	/** 이 서비스가 <b>소비만</b> 하는 토픽들. 만드는 건 남이지만, 잘못 만들어지면 손해는 여기서 본다. */
	@ParameterizedTest
	@ValueSource(strings = {
			TransferEvents.DEBITED,
			TransferEvents.CREDITED,
			TransferEvents.LEDGER_RECORDED,
			TransferEvents.DEBIT_FAILED,
			TransferEvents.CREDIT_FAILED,
			TransferEvents.DEBIT_REVERSED
	})
	void 소비하는_토픽도_기대한_파티션_수로_만들어진다(String topic) throws Exception {
		assertThat(partitionCountOf(topic))
				.as("%s가 1파티션으로 자동 생성되면, 나중에 늘려도 붙어 있던 컨슈머는 모른다", topic)
				.isEqualTo(EXPECTED_PARTITIONS);
	}

	/** 발행하는 토픽은 이미 선언되어 있다 — 비교 대상으로 함께 확인한다. */
	@Test
	void 발행하는_토픽은_원래_잘_만들어진다() throws Exception {
		assertThat(partitionCountOf("transfer.requested")).isEqualTo(EXPECTED_PARTITIONS);
	}

	private int partitionCountOf(String topic) throws Exception {
		try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
			Map<String, TopicDescription> described =
					admin.describeTopics(List.of(topic)).allTopicNames().get();
			return described.get(topic).partitions().size();
		}
	}
}
