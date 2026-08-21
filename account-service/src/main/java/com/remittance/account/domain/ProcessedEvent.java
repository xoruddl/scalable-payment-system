package com.remittance.account.domain;

import com.remittance.account.support.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * 이미 처리한 이벤트의 흔적. <b>컨슈머 멱등성</b>을 위해 존재한다.
 *
 * <p>Outbox 릴레이는 at-least-once라 같은 이벤트가 두 번 올 수 있다.
 * 잔액 변경은 두 번 적용되면 그대로 사고이므로, "이 이벤트를 처리했다"는 기록을
 * <b>잔액 변경과 같은 트랜잭션</b>에 남긴다. 둘이 함께 커밋되므로
 * "처리는 했는데 기록이 없어 또 처리하는" 틈이 생기지 않는다.
 *
 * <p>중복 감지는 PK unique 제약에 맡긴다. 조회 후 INSERT하면 두 컨슈머 스레드가
 * 동시에 "없다"를 보고 둘 다 처리하는 경합이 남기 때문이다.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent implements Persistable<String> {

	/** {@code <이벤트 타입>:<송금 ID>} 형태. 같은 송금의 같은 단계는 한 번만 처리된다. */
	@Id
	@Column(name = "event_key", length = 150)
	private String eventKey;

	@Column(nullable = false, updatable = false)
	private Instant processedAt;

	/**
	 * PK를 애플리케이션이 직접 지정하므로 Spring Data는 이 엔티티를 "이미 존재하는 것"으로 보고
	 * INSERT 대신 merge(=UPDATE)를 시도한다. 그러면 중복 이벤트가 unique 제약에 걸리지 않고
	 * <b>조용히 통과</b>해 이중 처리가 된다. INSERT를 강제해 제약 위반이 드러나게 한다.
	 * (transfer-service의 IdempotencyKey에서 같은 함정을 겪었다.)
	 */
	@Transient
	private boolean newEntity = true;

	public ProcessedEvent(String eventType, UUID transferId) {
		this.eventKey = eventType + ":" + transferId;
		this.processedAt = Timestamps.now();
	}

	@Override
	public String getId() {
		return eventKey;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		this.newEntity = false;
	}
}
