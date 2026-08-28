package com.remittance.transfer.service;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.domain.IdempotencyKey;
import com.remittance.transfer.domain.IdempotencyStatus;
import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.exception.IdempotencyInProgressException;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.outbox.TransferOutboxRecorder;
import com.remittance.transfer.repository.IdempotencyKeyRepository;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 Step 6b — <b>접수 도중 죽은 흔적을 안전하게 걷어낸다.</b>
 *
 * <p>접수는 세 번의 커밋으로 이뤄진다. 키 선점 → 송금 저장 → 키에 결과 기록.
 * 중간에 죽으면 키가 {@code IN_PROGRESS}로 남는데, <b>어디서 죽었느냐에 따라 처신이 정반대다.</b>
 *
 * <pre>
 *   선점 후 · 송금 저장 전에 죽음   ─▶ 송금이 없다.  키를 놓아줘야 한다.
 *   송금 저장 후 · 기록 전에 죽음   ─▶ 송금이 있다.  놓아주면 두 번째 송금이 생긴다.
 * </pre>
 *
 * <p>Step 6b 전에는 이 둘을 구분할 근거가 없어 <b>둘 다 영원히 409</b>였다. 송금에 키를 남기면서
 * 비로소 갈라진다 — 그 키로 커밋된 송금이 있는지 물어보면 된다.
 *
 * <p>여기서 가장 위험한 건 <b>살아 있는 접수의 키를 뺏는 것</b>이다. 그러면 같은 키로 두 건이
 * 접수되어, 멱등성이라는 계약 자체가 무너진다. 그래서 "푼다"만큼 <b>"언제 안 푸는가"</b>를 확인한다.
 */
@SpringBootTest
class IdempotencyRecoveryTest extends AbstractIntegrationTest {

	@Autowired
	private TransferService transferService;

	@Autowired
	private IdempotencyService idempotencyService;

	@Autowired
	private IdempotencyKeyRepository idempotencyKeyRepository;

	@Autowired
	private TransferRepository transferRepository;

	@Autowired
	private TransferOutboxRecorder outboxRecorder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final UUID fromAccountId = UUID.randomUUID();
	private final UUID toAccountId = UUID.randomUUID();

	private CreateTransferRequest request() {
		return CreateTransferRequest.internal(fromAccountId, toAccountId, new BigDecimal("1000.00"), "KRW", null);
	}

	private String newKey() {
		return UUID.randomUUID().toString();
	}

	/** 키만 선점하고 멈춘 상태 — 송금 저장 직전에 죽은 흔적이다. */
	private void reserveOnly(String key) {
		idempotencyService.reserve(key, idempotencyService.hash(request()));
	}

	/** 키 선점과 송금 저장까지 하고 멈춘 상태 — 키에 결과를 적기 직전에 죽은 흔적이다. */
	private Transfer reserveAndSaveTransfer(String key) {
		reserveOnly(key);
		return outboxRecorder.record(
				Transfer.builder()
						.fromAccountId(fromAccountId)
						.toAccountId(toAccountId)
						.amount(new BigDecimal("1000.00"))
						.currency("KRW")
						.idempotencyKey(key)
						.build(),
				TransferEventType.REQUESTED);
	}

	/**
	 * 키가 오래 묶여 있던 것처럼 만든다. 생성 시각은 엔티티로 못 바꾸므로 직접 손댄다.
	 *
	 * <p>절대 시각을 새로 써넣지 않고 <b>저장된 값에서 빼는</b> 이유는 시간대 때문이다.
	 * Hibernate는 {@code Instant}를 UTC로 저장하는데 {@code Timestamp.from()}은 로컬 시각으로 쓴다.
	 * 그대로 넣으면 시차만큼 어긋나 <b>과거로 민 값이 오히려 미래가 된다</b> (실제로 겪음).
	 */
	private void backdate(String key, Duration age) {
		jdbcTemplate.update(
				"UPDATE idempotency_keys SET created_at = created_at - INTERVAL ? SECOND"
						+ " WHERE idempotency_key = ?",
				age.toSeconds(), key);
	}

	/**
	 * 이 키로 저장된 송금이 몇 건인가.
	 *
	 * <p>{@code findByIdempotencyKey}는 {@code Optional}이라 두 건이 있어도 1을 넘길 수 없다 —
	 * 정작 확인하고 싶은 게 <b>두 건이 생겼는가</b>인데 그걸 못 센다. 목록으로 세야 한다.
	 */
	private int transferCountFor(String key) {
		return transferRepository.findByIdempotencyKeyIn(List.of(key)).size();
	}

	@Test
	void 접수하면_송금에_멱등성_키가_남는다() {
		String key = newKey();

		Transfer transfer = transferService.requestTransfer(key, request());

		assertThat(transfer.getIdempotencyKey())
				.as("이게 없으면 묶인 키가 어느 쪽 사고인지 영영 알 수 없다")
				.isEqualTo(key);
	}

	/**
	 * 접수는 커밋됐고 키에 적기 직전에 죽은 경우. 전에는 이 송금을 영영 돌려받지 못했다 —
	 * 돈은 이미 움직이기 시작했는데 사용자에게는 계속 409만 나갔다.
	 */
	@Test
	void 접수는_됐는데_키에_못_적은_경우_그_송금을_돌려준다() {
		String key = newKey();
		Transfer stranded = reserveAndSaveTransfer(key);

		Transfer replayed = transferService.requestTransfer(key, request());

		assertThat(replayed.getTransferId())
				.as("새로 만들지 않고 이미 접수된 그 송금이어야 한다")
				.isEqualTo(stranded.getTransferId());
		assertThat(transferCountFor(key)).isEqualTo(1);
	}

	/** 전진 복구는 키도 마저 닫는다. 안 닫으면 다음 재요청이 같은 조회를 또 하게 된다. */
	@Test
	void 전진_복구하면_키가_COMPLETED로_닫힌다() {
		String key = newKey();
		Transfer stranded = reserveAndSaveTransfer(key);

		transferService.requestTransfer(key, request());

		IdempotencyKey recovered = idempotencyKeyRepository.findById(key).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
		assertThat(recovered.getTransferId()).isEqualTo(stranded.getTransferId());
	}

	/**
	 * 접수가 커밋되지 않은 채 오래 묶인 키. 송금이 없으니 놓아줘도 두 번째 송금이 생길 수 없다.
	 * 전에는 이것도 영원히 409라서, 그 키를 쓰던 사용자는 송금 자체가 막혔다.
	 */
	@Test
	void 접수가_커밋되지_않은_묵은_키는_풀리고_새로_접수된다() {
		String key = newKey();
		reserveOnly(key);
		backdate(key, Duration.ofMinutes(30));

		Transfer accepted = transferService.requestTransfer(key, request());

		assertThat(accepted.getTransferId()).isNotNull();
		assertThat(transferCountFor(key))
				.as("풀어준 뒤 접수는 딱 한 번만 일어나야 한다")
				.isEqualTo(1);
	}

	/**
	 * <b>가장 위험한 경우.</b> 방금 선점된 키는 지금 다른 스레드가 접수 중일 수 있다.
	 * 여기서 뺏으면 같은 키로 두 건이 접수되어 멱등성이 통째로 무너진다.
	 * 몇 밀리초면 끝나는 일이라, 확신이 없으면 기다리게 하는 편이 옳다.
	 */
	@Test
	void 방금_선점된_키는_송금이_없어도_뺏지_않는다() {
		String key = newKey();
		reserveOnly(key);

		assertThatThrownBy(() -> transferService.requestTransfer(key, request()))
				.isInstanceOf(IdempotencyInProgressException.class);

		assertThat(idempotencyKeyRepository.findById(key))
				.as("진행 중일 수 있는 키를 지워버리면 그 접수는 결과를 적을 곳을 잃는다")
				.isPresent();
		assertThat(transferCountFor(key)).isZero();
	}

	/**
	 * 키 판정이 어떤 이유로든 뚫려도, 같은 키로 두 번째 송금이 저장되는 것 자체를 DB가 막는다.
	 * 판정 로직에 기대는 것과 <b>구조적으로 불가능하게 만드는 것</b>은 다르다.
	 */
	@Test
	void 같은_키로_두_번째_송금은_DB가_막는다() {
		String key = newKey();
		reserveAndSaveTransfer(key);

		assertThatThrownBy(() -> transferRepository.saveAndFlush(
				Transfer.builder()
						.fromAccountId(fromAccountId)
						.toAccountId(toAccountId)
						.amount(new BigDecimal("1000.00"))
						.currency("KRW")
						.idempotencyKey(key)
						.build()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
