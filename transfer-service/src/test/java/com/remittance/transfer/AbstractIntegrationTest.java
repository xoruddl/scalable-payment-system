package com.remittance.transfer;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 이 서비스의 통합 테스트 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 *
 * <p><b>운영과 같은 MySQL을 쓴다.</b> 예전에는 H2(MODE=MySQL)로 돌렸는데, 방언 차이 때문에
 * 테스트를 전부 통과하고도 MySQL에서만 터지는 버그가 두 번 있었다.
 * <ul>
 *   <li>Outbox의 {@code @Lob} payload가 MySQL에서 TINYTEXT로 만들어져 "Data too long"</li>
 *   <li>나노초 정밀도 시각이 DATETIME(6)에 잘려, 멱등 재요청 응답이 최초와 달라짐</li>
 * </ul>
 * 예약어({@code key})·컬럼 타입·정밀도 같은 차이는 진짜 MySQL로 돌려야만 드러난다.
 *
 * <p>Java는 단일 상속이라 이 서비스가 필요로 하는 컨테이너를 한 곳에 모아둔다.
 * 특정 컨테이너가 필요 없는 테스트도 같이 뜨지만, 싱글턴이라 JVM당 한 번만 기동된다.
 *
 * <p><b>싱글턴 컨테이너 패턴</b>을 쓴다 — {@code @Testcontainers} + {@code @Container}는
 * 테스트 클래스가 끝날 때마다 컨테이너를 멈춰서, 이 베이스를 상속한 두 번째 클래스부터
 * "Unable to connect"로 실패한다.
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
					.withDatabaseName("transfer_db")
					// MySQL 8의 caching_sha2_password 때문에 필요 (운영 설정과 동일)
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
