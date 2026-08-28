package com.remittance.ledger.config;

import com.remittance.ledger.AbstractIntegrationTest;
import com.remittance.ledger.domain.Transaction;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 Step 2 — 선언한 인덱스가 <b>실제 컬렉션에</b> 있는지 본다.
 *
 * <p>이 검증이 없어서 놓쳤다. {@code Transaction}에는 {@code @Indexed}가 넷이나 붙어 있었지만
 * 실제로 만들어진 건 {@code _id_} 하나뿐이었고(Spring Data MongoDB 3.0부터 자동 생성이 기본 꺼짐),
 * <b>기능은 전부 정상이라 아무도 몰랐다.</b> 느려질 뿐이었고, 그마저 문서가 쌓여야 드러났다.
 * 2026-08-23 부하 측정에서 MongoDB CPU 89%를 보고서야 찾았다.
 *
 * <p>그래서 <b>애노테이션을 읽지 않고 서버에 직접 묻는다.</b> 애노테이션을 확인하는 검증이었다면
 * 원래도 통과했을 것이다 — 애노테이션은 처음부터 멀쩡했다.
 */
@SpringBootTest
class MongoIndexInitializerTest extends AbstractIntegrationTest {

	@Autowired
	private ReactiveMongoTemplate mongoTemplate;

	@Test
	void 선언한_인덱스가_실제로_만들어져_있다() {
		List<String> indexNames = indexes().stream().map(IndexInfo::getName).toList();

		assertThat(indexNames).contains(
				"transactionId",          // 중복 방지 (unique)
				"transferId",             // 송금 한 건의 분개 조회 — 리스너가 메시지마다 부른다
				"accountId",              // 계좌별 원장 조회
				"accountId_recordedAt");  // 계좌별 최신순 페이지
	}

	/**
	 * {@code transferId} 인덱스는 <b>가장 뜨거운 경로</b>다.
	 * {@code BalanceChangedConsumer}가 메시지 한 건마다 {@code findByTransferId}를 부르므로,
	 * 이게 없으면 메시지마다 컬렉션 전체를 훑는다.
	 */
	@Test
	void transferId_조회가_컬렉션을_훑지_않는다() {
		Document explain = mongoTemplate.getCollection("transactions")
				.flatMap(collection -> Mono.from(collection.aggregate(
						List.of(new Document("$match", new Document("transferId", UUID.randomUUID()))))
						.explain()))
				.block();

		assertThat(explain).as("실행 계획을 못 받았다").isNotNull();
		assertThat(explain.toJson())
				.as("transferId 조회가 COLLSCAN으로 떨어졌다 — 인덱스가 없다는 뜻이다")
				.contains("IXSCAN");
	}

	/**
	 * unique 인덱스는 성능이 아니라 <b>원장에 같은 줄이 두 번 들어가는 것을 막는 장치</b>다.
	 * 이름만 있는지 보지 않고 실제로 {@code unique} 속성이 붙었는지 확인한다.
	 */
	@Test
	void transactionId는_unique다() {
		IndexInfo transactionId = indexes().stream()
				.filter(index -> index.getName().equals("transactionId"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("transactionId 인덱스가 없다"));

		assertThat(transactionId.isUnique()).as("중복을 막지 못한다").isTrue();
	}

	private List<IndexInfo> indexes() {
		return mongoTemplate.indexOps(Transaction.class).getIndexInfo().collectList().block();
	}
}
