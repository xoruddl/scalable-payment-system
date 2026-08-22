package com.remittance.notification;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 이 서비스의 통합 테스트 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 *
 * <p><b>운영과 같은 MySQL을 쓴다.</b> 인메모리 DB는 방언 차이 때문에 통과하고도 MySQL에서만
 * 터지는 버그를 만든다 (다른 서비스에서 두 번 겪었다). 특히 이 서비스는 <b>unique 제약이
 * 중복 알림을 막는 핵심</b>이라, 제약이 실제로 걸리는 DB에서 확인해야 의미가 있다.
 *
 * <p><b>싱글턴 컨테이너 패턴</b>을 쓴다 — {@code @Testcontainers} + {@code @Container}는
 * 테스트 클래스가 끝날 때마다 컨테이너를 멈춰서, 이 베이스를 상속한 두 번째 클래스부터 실패한다.
 */
/**
 * <b>{@code integration}</b> 태그가 여기 붙어 있고 JUnit의 {@code @Tag}는 상속되므로,
 * 이 클래스를 상속하는 테스트는 전부 자동으로 통합 테스트로 분류된다.
 * {@code ./gradlew unitTest}에서 제외되어 Docker 없이 빠른 검증이 가능해진다.
 */
@Tag("integration")
public abstract class AbstractIntegrationTest {

	private static final MySQLContainer<?> MYSQL_CONTAINER =
			new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
					.withDatabaseName("notification_db")
					.withUrlParam("allowPublicKeyRetrieval", "true")
					.withUrlParam("useSSL", "false");

	private static final KafkaContainer KAFKA_CONTAINER =
			new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

	static {
		MYSQL_CONTAINER.start();
		KAFKA_CONTAINER.start();
	}

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
		registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
		registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
	}
}
