package com.remittance.ledger.config;

import com.remittance.ledger.domain.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Transaction}에 선언된 인덱스를 <b>실제로 만든다</b> (Phase 6 Step 2).
 *
 * <h2>왜 필요한가 — 애노테이션은 있는데 인덱스가 없었다</h2>
 * {@code Transaction}에는 {@code @Indexed}가 넷이나 붙어 있다. 그런데 2026-08-23 측정에서
 * 실제 컬렉션을 열어 보니 인덱스가 <b>{@code _id_} 하나뿐</b>이었다.
 * <b>Spring Data MongoDB는 3.0부터 자동 인덱스 생성이 기본으로 꺼져 있다.</b>
 * 켜 준 적이 없으니 그 애노테이션들은 전부 장식이었다.
 *
 * <p>대가는 컸다. {@code BalanceChangedConsumer}가 메시지마다 {@code findByTransferId}를 한 번씩
 * 부르는데, 인덱스가 없으니 <b>메시지 한 건마다 59,034건짜리 컬렉션을 통째로 훑었다.</b>
 * MongoDB CPU가 89%였고 ledger 리스너는 초당 40건에서 멈췄다.
 *
 * <p>더 나쁜 건 <b>문서가 쌓일수록 느려진다</b>는 점이다. 부하 시험을 오래 돌릴수록 나빠지는데,
 * 테스트에서는 컬렉션이 작아 절대 드러나지 않는다.
 *
 * <h2>속도만의 문제가 아니다</h2>
 * {@code transactionId}의 unique 인덱스는 <b>원장에 같은 줄이 두 번 들어가는 것을 막는 장치</b>다.
 * 지금까지 그 장치가 아예 없었다. 마침 중복이 0건이라 사고가 나지 않았을 뿐,
 * 막아주고 있던 게 아니라 운이 좋았던 것이다.
 *
 * <h2>왜 {@code auto-index-creation: true} 한 줄이 아닌가</h2>
 * 그게 더 짧지만, {@code src/test/resources/application.yml}이 운영 yml을 통째로 가려서
 * <b>테스트는 인덱스 없이 돌게 된다.</b> 방금 놓친 것을 또 못 잡는다는 뜻이다
 * (같은 함정을 {@code MetricsDistributionConfig}에서 이미 밟았다).
 *
 * <p>정의는 여전히 도메인의 애노테이션 한 곳에 있다 — {@link IndexResolver}가 그걸 그대로 읽는다.
 * 여기서 명시하는 것은 <b>정의가 아니라 "만든다"는 사실</b>뿐이다.
 *
 * <h2>왜 {@code ApplicationRunner}가 아닌가</h2>
 * {@code ApplicationRunner}는 컨텍스트가 다 뜬 뒤에 돈다. 그 사이 Kafka 리스너가 먼저 시작해
 * 메시지를 처리하는데, 그때 중복이 하나라도 들어오면 <b>뒤이은 unique 인덱스 생성이 실패한다.</b>
 * 싱글턴 초기화 단계에서 끝내야 그 창이 없다.
 *
 * <h2>여기가 끝이 아니다</h2>
 * 기동할 때 인덱스를 만드는 방식은 <b>큰 컬렉션에서는 그동안 기동이 막히고</b>, 이미 중복이 있으면
 * unique 인덱스 생성이 실패한다. 즉 이건 손수 만든 중간 단계다.
 * {@code ddl-auto: update} → Flyway와 <b>같은 문제</b>이므로(스키마를 앱이 암묵적으로 만든다),
 * Flyway를 넣을 때 Mongock으로 함께 옮긴다 — {@code DECISIONS.md} 참고.
 */
@Component
@RequiredArgsConstructor
public class MongoIndexInitializer implements InitializingBean {

	private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

	private final ReactiveMongoTemplate mongoTemplate;
	private final MongoMappingContext mappingContext;

	@Override
	public void afterPropertiesSet() {
		ReactiveIndexOperations indexOps = mongoTemplate.indexOps(Transaction.class);
		List<String> ensured = new ArrayList<>();
		for (IndexDefinition definition : IndexResolver.create(mappingContext).resolveIndexFor(Transaction.class)) {
			ensured.add(indexOps.ensureIndex(definition).block());
		}
		log.info("원장 인덱스를 확인했다 (없으면 만든다): {}", ensured);
	}
}
