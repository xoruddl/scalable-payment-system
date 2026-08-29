package com.remittance.account.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 보관 기간이 지난 Outbox 행을 <b>청크 하나 = 트랜잭션 하나</b>로 지운다.
 *
 * <h2>왜 별도 빈인가 — {@link OutboxRelay}와 같은 이유다</h2>
 * 같은 빈 안에서 자기 메서드를 부르면 {@code @Transactional} 프록시를 타지 않는다.
 * {@link OutboxRetention}이 직접 삭제 쿼리를 부르면 트랜잭션이 없어
 * <b>{@code TransactionRequiredException}으로 매 주기 터진다.</b>
 *
 * <p>그리고 청크마다 트랜잭션이 따로여야 한다. {@code sweep()}에 트랜잭션을 걸면
 * 한 주기 전체가 한 트랜잭션이 되어 <b>끊어 지우는 의미가 사라진다</b> —
 * 락을 오래 쥐고 삭제 흔적도 한꺼번에 쏟아진다.
 *
 * <h2>실제로 당했다 (2026-08-29)</h2>
 * 이 빈 없이 배포했더니 정리 배치가 <b>매 주기 예외로 죽고 있었다.</b> 통합 테스트는
 * green이었는데, <b>테스트 메서드에 {@code @Transactional}이 붙어 있어 운영 코드에 없는
 * 트랜잭션을 테스트가 대신 만들어줬기 때문이다.</b> 홈서버 로그를 보고서야 알았다.
 */
@Component
@RequiredArgsConstructor
public class OutboxChunkDeleter {

	private final OutboxEventRepository repository;

	/** @return 실제로 지운 건수. {@code limit}보다 적으면 더 지울 게 없다는 뜻이다. */
	@Transactional
	public int deleteChunk(Instant before, int limit) {
		return repository.deletePublishedBefore(before, limit);
	}
}
