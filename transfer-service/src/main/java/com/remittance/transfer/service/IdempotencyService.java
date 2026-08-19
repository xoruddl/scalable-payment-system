package com.remittance.transfer.service;

import com.remittance.transfer.domain.IdempotencyKey;
import com.remittance.transfer.repository.IdempotencyKeyRepository;
import com.remittance.transfer.web.dto.CreateTransferRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency-Key의 예약/조회/종결을 담당한다.
 *
 * <p>각 메서드는 짧은 독립 트랜잭션으로 동작해야 한다. 특히 {@link #reserve}는 송금 처리가
 * 시작되기 <b>전에 커밋</b>되어야, 같은 키로 동시에 들어온 두 번째 요청이 그 행을 보고 막힌다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

	/** 키 보관 기간. 만료된 키 정리는 Step 5의 배치에서 다룬다. */
	private static final Duration RETENTION = Duration.ofDays(1);

	private final IdempotencyKeyRepository idempotencyKeyRepository;

	/**
	 * 요청 내용을 정규화해 해시한다. JSON 원문이 아니라 필드 값으로 만들기 때문에
	 * 공백·필드 순서가 달라도 같은 요청이면 같은 해시가 나온다.
	 * 금액은 3000과 3000.00이 같은 요청으로 취급되도록 정규화한다.
	 */
	public String hash(CreateTransferRequest request) {
		String canonical = String.join("|",
				String.valueOf(request.fromAccountId()),
				String.valueOf(request.toAccountId()),
				canonicalAmount(request.amount()),
				String.valueOf(request.currency()),
				request.memo() == null ? "" : request.memo());
		return sha256(canonical);
	}

	private String canonicalAmount(BigDecimal amount) {
		return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
	}

	private String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
		}
	}

	/**
	 * 키를 IN_PROGRESS로 선점한다.
	 * 이미 같은 키가 있으면 DB unique 제약에 걸려 DataIntegrityViolationException이 발생하며,
	 * 호출자가 이를 잡아 기존 키를 재조회한다.
	 */
	@Transactional
	public void reserve(String key, String requestHash) {
		idempotencyKeyRepository.saveAndFlush(IdempotencyKey.builder()
				.key(key)
				.requestHash(requestHash)
				.expiresAt(Instant.now().plus(RETENTION))
				.build());
	}

	@Transactional(readOnly = true)
	public Optional<IdempotencyKey> find(String key) {
		return idempotencyKeyRepository.findById(key);
	}

	@Transactional
	public void complete(String key, UUID transferId) {
		idempotencyKeyRepository.findById(key).ifPresent(idempotencyKey -> idempotencyKey.complete(transferId));
	}

	@Transactional
	public void fail(String key, UUID transferId) {
		idempotencyKeyRepository.findById(key).ifPresent(idempotencyKey -> idempotencyKey.fail(transferId));
	}
}
