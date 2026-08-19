package com.remittance.transfer;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Kafka가 필요한 통합 테스트의 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 *
 * <p>싱글턴 컨테이너 패턴을 쓴다 — {@code @Testcontainers} + {@code @Container}는 테스트 클래스마다
 * 컨테이너를 멈춰서, 베이스를 상속한 클래스가 둘 이상이면 두 번째부터 연결에 실패한다.
 * (Step 2에서 Redis로 실제 겪은 문제)
 */
public abstract class AbstractKafkaIntegrationTest {

	private static final KafkaContainer KAFKA_CONTAINER =
			new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

	static {
		KAFKA_CONTAINER.start();
	}

	@DynamicPropertySource
	static void kafkaProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
	}
}
