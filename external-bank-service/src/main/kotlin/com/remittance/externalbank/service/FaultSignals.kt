package com.remittance.externalbank.service

import java.util.UUID

/**
 * 응답을 주지 않기로 했다는 표시. **입금은 이미 커밋된 뒤에** 던져진다.
 *
 * <p>이 순서가 이 서비스의 전부다. 커밋 전에 던지면 트랜잭션이 롤백되어
 * "아무 일도 안 일어남"이 되고, 그러면 보내는 쪽에 아무 문제도 생기지 않는다.
 */
class ResponseSwallowedException(val transferId: UUID) :
	RuntimeException("응답을 삼킨다 (transferId=$transferId) — 입금은 이미 처리됐다")

/** 이번엔 답할 수 없다는 표시. **업무를 시작하기도 전에** 던져지므로 입금은 없다. */
class TemporaryFailureException :
	RuntimeException("일시적인 장애를 흉내낸다 — 이 요청은 처리되지 않았다")
