package com.remittance.externalbank

import org.junit.jupiter.api.Tag
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 상대 은행의 통합 테스트 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 *
 * <p>다른 서비스와 같은 이유로 **운영과 같은 MySQL**을 쓰고, **싱글턴 컨테이너 패턴**을 쓴다
 * (`@Container`는 클래스마다 컨테이너를 멈춰서 두 번째 클래스가 실패한다).
 */
@Tag("integration")
abstract class AbstractIntegrationTest {

	companion object {
		// org.testcontainers.containers.MySQLContainer는 deprecated다.
		// Kotlin 모듈은 allWarningsAsErrors라 컴파일이 막혀서 새 자리를 쓴다 —
		// Java 모듈들은 경고만 나서 아직 옛 패키지를 쓰고 있다.
		private val MYSQL: MySQLContainer =
			MySQLContainer(DockerImageName.parse("mysql:8.0"))
				.withDatabaseName("external_bank_db")
				.withUrlParam("allowPublicKeyRetrieval", "true")
				.withUrlParam("useSSL", "false")

		init {
			MYSQL.start()
		}

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("spring.datasource.url", MYSQL::getJdbcUrl)
			registry.add("spring.datasource.username", MYSQL::getUsername)
			registry.add("spring.datasource.password", MYSQL::getPassword)
		}
	}
}
