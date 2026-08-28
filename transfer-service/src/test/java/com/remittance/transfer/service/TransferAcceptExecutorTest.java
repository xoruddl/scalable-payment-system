package com.remittance.transfer.service;

import com.remittance.transfer.AbstractIntegrationTest;
import com.remittance.transfer.domain.IdempotencyStatus;
import com.remittance.transfer.outbox.OutboxEventRepository;
import com.remittance.transfer.outbox.TransferEventType;
import com.remittance.transfer.repository.IdempotencyKeyRepository;
import com.remittance.transfer.repository.TransferRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;

/**
 * 접수가 <b>한 트랜잭션</b>인지 본다 (Phase 6, 커밋 수 줄이기).
 *
 * <p>합쳤다는 것은 <b>커밋이 한 번</b>이라는 뜻이고, 그건 "둘 중 하나만 남는 상태가 없다"로만
 * 증명할 수 있다. 그래서 <b>키 결과를 적는 마지막 단계에서 일부러 터뜨리고</b>
 * 앞서 저장한 송금과 Outbox까지 함께 사라지는지 확인한다.
 *
 * <p>갈라져 있던 시절에는 이 테스트가 red다 — 송금은 이미 커밋됐으므로 남는다.
 * 실제로 그 상태를 위해 {@code TransferService#recoverInProgress}의 전진 복구가 있었다.
 * 그 코드는 지우지 않았지만(옛 행이 남아 있을 수 있다), <b>새로 만들어지지는 않는다.</b>
 */
@SpringBootTest
class TransferAcceptExecutorTest extends AbstractIntegrationTest {

	@Autowired
	private TransferAcceptExecutor acceptExecutor;

	@Autowired
	private IdempotencyService idempotencyService;

	@Autowired
	private TransferRepository transferRepository;

	@Autowired
	private IdempotencyKeyRepository idempotencyKeyRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@MockitoSpyBean
	private IdempotencyService spiedIdempotencyService;

	private CreateTransferRequest request() {
		return CreateTransferRequest.internal(UUID.randomUUID(), UUID.randomUUID(),
				new BigDecimal("1000"), "KRW", "한 트랜잭션 검증");
	}

	@Test
	void 접수가_성공하면_송금과_키_결과가_함께_남는다() {
		String key = UUID.randomUUID().toString();
		CreateTransferRequest request = request();
		idempotencyService.reserve(key, idempotencyService.hash(request));

		acceptExecutor.accept(key, request);

		assertThat(transferRepository.findByIdempotencyKey(key)).isPresent();
		assertThat(idempotencyKeyRepository.findById(key)).get()
				.extracting(k -> k.getStatus())
				.isEqualTo(IdempotencyStatus.COMPLETED);
	}

	@Test
	void 키_결과_기록이_실패하면_송금도_남지_않는다() {
		String key = UUID.randomUUID().toString();
		CreateTransferRequest request = request();
		idempotencyService.reserve(key, idempotencyService.hash(request));

		// 마지막 단계만 터뜨린다. 갈라져 있었다면 앞의 송금은 이미 커밋돼 살아남는다.
		willThrow(new IllegalStateException("일부러 터뜨린다"))
				.given(spiedIdempotencyService).complete(anyString(), any());

		assertThatThrownBy(() -> acceptExecutor.accept(key, request))
				.isInstanceOf(IllegalStateException.class);

		assertThat(transferRepository.findByIdempotencyKey(key))
				.as("한 트랜잭션이면 송금도 롤백된다 — '접수됐는데 키에 없는' 상태가 만들어지지 않는다")
				.isEmpty();
		assertThat(idempotencyKeyRepository.findById(key)).get()
				.as("키 선점은 별도 커밋이라 그대로 남는다 (동시 요청이 PK 충돌로 빨리 실패해야 하므로)")
				.extracting(k -> k.getStatus())
				.isEqualTo(IdempotencyStatus.IN_PROGRESS);
	}

	@Test
	void 접수는_Outbox_이벤트도_같은_트랜잭션에_남긴다() {
		String key = UUID.randomUUID().toString();
		CreateTransferRequest request = request();
		idempotencyService.reserve(key, idempotencyService.hash(request));

		UUID transferId = acceptExecutor.accept(key, request).getTransferId();

		assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(transferId))
				.as("접수됐는데 아무도 모르는 송금이 생기면 안 된다")
				.singleElement()
				.extracting(event -> event.getEventType())
				.isEqualTo(TransferEventType.REQUESTED.topic());
	}
}
