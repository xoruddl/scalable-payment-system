package com.remittance.account.config;

import com.remittance.account.exception.AccountNotFoundException;
import com.remittance.account.exception.ConcurrentUpdateException;
import com.remittance.account.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 Step 1 — <b>붐빈다는 이유로 돈을 버리지 않는다.</b>
 *
 * <p>2026-08-23 핫 계좌 측정에서 송금 9건이 {@code DEBIT_COMPLETED}로 영영 갇혔다.
 * 출금은 됐는데 입금이 안 된 채로 멈춘 것이고, 원인은 락을 3초 안에 못 잡은 실패가
 * <b>여느 실패와 똑같이 취급돼 세 번 만에 DLT로 죽은</b> 것이었다.
 *
 * <p>여기서 확인하는 것은 <b>어떤 실패에 어떤 재시도 정책이 붙는가</b>다.
 * "무한 재시도가 실제로 일어나는지"는 부하를 걸어야 보이지만, 정책이 잘못 붙으면
 * 그 부하 시험도 볼 것이 없다. <b>그리고 이건 틀려도 아무 증상이 없다</b> —
 * 평상시에는 경합이 없어 두 정책이 똑같이 보인다.
 */
class KafkaErrorHandlingBackOffTest {

	/** 재시도 횟수가 유한한지 본다. {@code STOP}이 나오면 거기서 포기하고 DLT로 간다. */
	private static boolean givesUp(BackOff backOff, int within) {
		BackOffExecution execution = backOff.start();
		for (int i = 0; i < within; i++) {
			if (execution.nextBackOff() == BackOffExecution.STOP) {
				return true;
			}
		}
		return false;
	}

	@Test
	void 락을_못_잡은_것은_포기하지_않는다() {
		BackOff backOff = KafkaErrorHandlingConfig.backOffFor(
				new LockAcquisitionException("lock:account:x", Duration.ofSeconds(3)));

		assertThat(givesUp(backOff, 1_000))
				.as("붐빈다는 이유로 돈을 버리게 된다")
				.isFalse();
	}

	@Test
	void 낙관적_락_충돌도_포기하지_않는다() {
		BackOff backOff = KafkaErrorHandlingConfig.backOffFor(new ConcurrentUpdateException(UUID.randomUUID()));

		assertThat(givesUp(backOff, 1_000)).isFalse();
	}

	/**
	 * 리스너에서 난 예외는 spring-kafka가 {@link ListenerExecutionFailedException}으로 감싸서 올린다.
	 * <b>맨 바깥만 보면 경합인 줄 모른다</b> — 실제 운영에서 오는 모양이 이쪽이다.
	 */
	@Test
	void 감싸져_올라와도_경합인_줄_안다() {
		Exception wrapped = new ListenerExecutionFailedException("리스너 실패",
				new LockAcquisitionException("lock:account:x", Duration.ofSeconds(3)));

		assertThat(KafkaErrorHandlingConfig.isContention(wrapped)).isTrue();
		assertThat(givesUp(KafkaErrorHandlingConfig.backOffFor(wrapped), 1_000)).isFalse();
	}

	/**
	 * 경합이 아닌 실패까지 무한 재시도하면 <b>진짜 못 고치는 메시지가 파티션을 영영 막는다.</b>
	 * 그건 DLT를 둔 이유 자체를 없애는 것이다.
	 */
	@Test
	void 경합이_아닌_실패는_예전처럼_포기한다() {
		BackOff backOff = KafkaErrorHandlingConfig.backOffFor(new AccountNotFoundException(UUID.randomUUID()));

		assertThat(givesUp(backOff, 100))
				.as("못 고치는 메시지가 파티션을 영영 막게 된다")
				.isTrue();
	}
}
