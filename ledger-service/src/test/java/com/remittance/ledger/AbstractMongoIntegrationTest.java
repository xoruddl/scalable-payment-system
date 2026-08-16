package com.remittance.ledger;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Mongo가 필요한 통합 테스트의 공통 베이스. 로컬에서 Docker가 실행 중이어야 한다.
 */
@Testcontainers
public abstract class AbstractMongoIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO_CONTAINER = new MongoDBContainer(DockerImageName.parse("mongo:7"));
}
