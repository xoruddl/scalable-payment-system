package com.remittance.ledger;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 이 서비스의 통합 테스트 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 *
 * <p>Java는 단일 상속이라 이 서비스가 필요로 하는 컨테이너를 한 곳에 모아둔다.
 * 특정 컨테이너가 필요 없는 테스트도 같이 뜨지만, 싱글턴이라 JVM당 한 번만 기동된다.
 *
 * <p><b>싱글턴 컨테이너 패턴</b>을 쓴다. {@code @Testcontainers} + {@code @Container} 조합은
 * 테스트 클래스가 끝날 때마다 컨테이너를 멈추기 때문에, 이 베이스를 상속한 클래스가 둘 이상이 되면
 * 두 번째부터 이미 죽은 컨테이너에 붙어 실패한다. (account-service에서 실제로 겪은 문제)
 */
/**
 * <b>{@code integration}</b> 태그가 여기 붙어 있고 JUnit의 {@code @Tag}는 상속되므로,
 * 이 클래스를 상속하는 테스트는 전부 자동으로 통합 테스트로 분류된다.
 * {@code ./gradlew unitTest}에서 제외되어 Docker 없이 빠른 검증이 가능해진다.
 */
@Tag("integration")
public abstract class AbstractIntegrationTest {

	private static final MongoDBContainer MONGO_CONTAINER =
			new MongoDBContainer(DockerImageName.parse("mongo:7"));

	private static final KafkaContainer KAFKA_CONTAINER =
			new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

	static {
		MONGO_CONTAINER.start();
		KAFKA_CONTAINER.start();
	}

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", MONGO_CONTAINER::getReplicaSetUrl);
		registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
	}
}
