package com.remittance.account.config;

import com.remittance.account.exception.AccountNotActiveException;
import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.exception.CurrencyMismatchException;
import com.remittance.account.exception.InsufficientBalanceException;
import com.remittance.account.exception.LockAcquisitionException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * 컨슈머가 실패했을 때 무엇을 할지 정한다 — <b>몇 번 다시 해보고, 그래도 안 되면 어디에 둘지.</b>
 *
 * <p>이걸 두지 않으면 spring-kafka 기본값이 적용되는데, 그 기본값은
 * <b>지연 없이 10번 재시도하고 로그만 남긴 뒤 오프셋을 커밋</b>한다. 즉 메시지가 조용히 사라진다.
 * 잠깐 DB가 끊긴 것뿐이었어도 1초 안에 10번을 몰아 시도하고 포기하므로, 회복될 틈도 없다.
 *
 * <p>바꾼 것은 두 가지다.
 * <ul>
 *   <li><b>지수 백오프</b>: 1초 → 2초 → 4초. 일시적인 장애가 회복될 시간을 준다.</li>
 *   <li><b>DLT</b>: 끝내 실패하면 {@code <원래 토픽>.DLT}로 보낸다. 사라지지 않고 남아야
 *       사람이 보고 처리할 수 있다 — 돈이 걸린 이벤트라 유실이 곧 사고다.</li>
 * </ul>
 *
 * <p>재시도가 의미 있으려면 <b>컨슈머가 멱등해야 한다.</b> 이 서비스는 처리 흔적(processed_events)으로
 * 그걸 보장하므로, 같은 이벤트를 몇 번 다시 처리해도 잔액은 한 번만 움직인다.
 *
 * <h2>경합은 포기하지 않는다 (Phase 6 Step 1)</h2>
 * 2026-08-23 핫 계좌 측정에서 <b>송금 9건이 영영 갇혔다.</b> 상태가 {@code DEBIT_COMPLETED} —
 * <b>출금은 됐는데 입금이 안 된 채로</b> 멈춘 것이고, 이건 이 시스템에서 가장 나쁜 결말이다.
 *
 * <p>원인은 여기였다. 락을 3초 안에 못 잡으면 {@link LockAcquisitionException}이 올라오는데,
 * 그게 여느 실패와 똑같이 취급돼 <b>재시도 세 번 만에 DLT로 죽었다.</b>
 *
 * <p>그런데 <b>락을 못 잡은 것은 "지금 붐빈다"는 뜻이지 처리할 수 없는 메시지가 아니다.</b>
 * 잠시 뒤에 다시 하면 된다. 여기에 횟수 제한을 두면 <b>붐빈다는 이유로 돈을 버리는 셈</b>이다.
 * 그래서 경합 계열 예외는 <b>횟수 제한 없이</b> 재시도한다.
 *
 * <p>대가는 <b>그 파티션이 그동안 막힌다</b>는 것이다. 하지만 그게 맞는 동작이다 —
 * 처리할 수 있는 것보다 많이 들어오는 중이니 <b>받는 속도를 늦추는 것(배압)</b>이 옳다.
 * 영영 막히지도 않는다. 락에는 TTL 3초가 있어 <b>붙들려 있는 상태 자체가 지속될 수 없다.</b>
 *
 * <p>같은 이유로 {@link ConcurrentUpdateException}(낙관적 락 재시도 소진)도 함께 넣는다.
 * 2026-08-23 비교 실험에서 이쪽은 <b>168건</b>을 갇히게 만들었다.
 */
@Configuration
public class KafkaErrorHandlingConfig {

	private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

	/** 첫 재시도까지 기다리는 시간. */
	private static final long INITIAL_INTERVAL_MS = 1_000;
	private static final double MULTIPLIER = 2.0;
	/** 아무리 늘어나도 이보다 오래 기다리지는 않는다. */
	private static final long MAX_INTERVAL_MS = 10_000;
	/** 재시도 횟수(최초 시도 제외). 1초 + 2초 + 4초를 쓰고 포기한다. */
	private static final int MAX_RETRIES = 3;

	/**
	 * 경합 때문에 실패한 것은 <b>포기하지 않는다.</b> 간격만 두고 계속 다시 한다.
	 *
	 * <p>1초로 잡은 이유: 락 TTL이 3초라 그보다 짧게 잡으면 아직 남의 락이 살아 있는 동안
	 * 헛되이 여러 번 두드린다. 반대로 너무 길면 붐빔이 풀린 뒤에도 놀게 된다.
	 */
	private static final BackOff CONTENTION_BACKOFF =
			new FixedBackOff(1_000, FixedBackOff.UNLIMITED_ATTEMPTS);

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
		DefaultErrorHandler errorHandler =
				new DefaultErrorHandler(deadLetterRecoverer(kafkaTemplate, meterRegistry), backOff());

		// 실패의 성격에 따라 재시도 정책을 다르게 준다.
		// 경합(붐빔)은 횟수 제한 없이, 나머지는 지수 백오프 세 번 뒤 DLT.
		errorHandler.setBackOffFunction((record, exception) -> backOffFor(exception));

		// 아래 실패들은 다시 시도해도 결과가 같다. 백오프를 낭비하지 말고 바로 DLT로 보낸다.
		// 이 예외들이 여기까지 올라왔다는 건 보상 단계가 실패했다는 뜻이므로 사람이 봐야 한다
		// (전진 단계의 업무적 실패는 TransferSagaService가 실패 이벤트로 바꿔 처리한다).
		errorHandler.addNotRetryableExceptions(
				AccountNotFoundException.class,
				InsufficientBalanceException.class,
				AccountNotActiveException.class,
				CurrencyMismatchException.class,
				// 본문을 못 읽는 메시지는 몇 번을 다시 읽어도 못 읽는다.
				JacksonException.class);
		return errorHandler;
	}

	/**
	 * 이 실패에 어떤 재시도 정책을 쓸지 고른다.
	 *
	 * <p>{@code static}으로 둔 이유는 <b>테스트에서 직접 부를 수 있게</b> 하기 위해서다.
	 * "무한 재시도가 실제로 일어나는지"는 통합 테스트로 재현하기 어렵지만,
	 * <b>어떤 예외에 어떤 정책이 붙는지</b>는 여기서 정확히 확인할 수 있다.
	 */
	static BackOff backOffFor(Exception exception) {
		return isContention(exception) ? CONTENTION_BACKOFF : backOff();
	}

	/**
	 * 경합 때문에 실패한 것인가.
	 *
	 * <p><b>원인 사슬을 따라간다.</b> 리스너에서 난 예외는 spring-kafka가
	 * {@code ListenerExecutionFailedException}으로 감싸서 올리므로, 맨 바깥만 보면 못 찾는다.
	 */
	static boolean isContention(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof LockAcquisitionException || cause instanceof ConcurrentUpdateException) {
				return true;
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}

	private static ExponentialBackOff backOff() {
		ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, MULTIPLIER);
		backOff.setMaxInterval(MAX_INTERVAL_MS);
		backOff.setMaxAttempts(MAX_RETRIES);
		return backOff;
	}

	/**
	 * DLT로 보내면서 <b>흔적을 남긴다</b> (Phase 5 Step 2).
	 *
	 * <p>2026-08-22 e2e에서 확인한 것: 본문이 깨진 메시지를 넣었더니 설계대로 DLT에 쌓이고
	 * 파티션도 막히지 않았는데, <b>WARN·ERROR 로그가 한 줄도 남지 않았다.</b>
	 * {@code DeadLetterPublishingRecoverer}는 성공적으로 보낸 사실을 알리지 않는다.
	 * 메시지 하나가 죽어서 사람 손을 기다리는 중인데 아무도 모르는 상태가 된다.
	 *
	 * <p>그래서 두 가지를 붙였다.
	 * <ul>
	 *   <li><b>WARN 로그</b> — 어느 토픽·파티션·오프셋의 무엇이 왜 죽었는지. 발행보다 <b>먼저</b>
	 *       남긴다. DLT 발행 자체가 실패해도 최소한 무엇을 잃었는지는 알아야 한다.</li>
	 *   <li><b>{@code remittance.kafka.dlt.published} 카운터</b> — 토픽별. 로그는 사람이 볼 때만
	 *       보이지만 이 값은 그래프에서 튄다. 토픽 수가 고정이라 라벨이 늘어날 걱정도 없다.</li>
	 * </ul>
	 *
	 * <p>파티션을 {@code -1}로 넘기는 게 핵심이다. 기본 동작은 <b>원래 메시지와 같은 번호의 파티션</b>으로
	 * 보내는데, 우리 토픽은 파티션이 3개인 반면 DLT는 자동 생성되며 1개짜리로 만들어진다.
	 * 그러면 2번 파티션으로 가야 할 메시지가 갈 곳이 없어 DLT 발행 자체가 실패한다 —
	 * 마지막 안전망이 조용히 무너지는 셈이다.
	 */
	private static ConsumerRecordRecoverer deadLetterRecoverer(
			KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
		DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafkaTemplate,
				(record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));

		return (ConsumerRecord<?, ?> record, Exception exception) -> {
			log.warn("메시지를 DLT로 보낸다 - 사람이 봐야 한다 (topic={}, partition={}, offset={}, key={}, reason={})",
					record.topic(), record.partition(), record.offset(), record.key(),
					exception.getMessage(), exception);
			Counter.builder("remittance.kafka.dlt.published")
					.description("DLT로 보낸 메시지 수")
					.tag("topic", record.topic())
					.register(meterRegistry)
					.increment();
			publisher.accept(record, exception);
		};
	}
}
