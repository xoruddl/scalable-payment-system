package com.remittance.ledger.service;

import com.remittance.ledger.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionCursorTest {

	@Test
	void 인코딩_후_디코딩하면_원본과_같다() {
		TransactionCursor cursor = new TransactionCursor(Instant.ofEpochMilli(Instant.now().toEpochMilli()),
				UUID.randomUUID());

		TransactionCursor decoded = TransactionCursor.decode(cursor.encode());

		assertThat(decoded.recordedAt()).isEqualTo(cursor.recordedAt());
		assertThat(decoded.transactionId()).isEqualTo(cursor.transactionId());
	}

	@Test
	void 잘못된_cursor는_예외() {
		assertThatThrownBy(() -> TransactionCursor.decode("not-a-valid-cursor"))
				.isInstanceOf(InvalidCursorException.class);
	}
}
