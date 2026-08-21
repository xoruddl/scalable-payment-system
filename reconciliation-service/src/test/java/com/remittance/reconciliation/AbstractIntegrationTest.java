package com.remittance.reconciliation;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 이 서비스의 통합 테스트 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 *
 * <p><b>운영과 같은 MySQL을 쓴다.</b> 인메모리 DB는 방언 차이 때문에 통과하고도 MySQL에서만
 * 터지는 버그를 만든다 (다른 서비스에서 두 번 겪었다).
 *
 * <p><b>싱글턴 컨테이너 패턴</b>을 쓴다 — {@code @Testcontainers} + {@code @Container}는
 * 테스트 클래스가 끝날 때마다 컨테이너를 멈춰서, 이 베이스를 상속한 두 번째 클래스부터 실패한다.
 */
public abstract class AbstractIntegrationTest {

	private static final MySQLContainer<?> MYSQL_CONTAINER =
			new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
					.withDatabaseName("reconciliation_db")
					.withUrlParam("allowPublicKeyRetrieval", "true")
					.withUrlParam("useSSL", "false");

	static {
		MYSQL_CONTAINER.start();
	}

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
		registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
	}
}
