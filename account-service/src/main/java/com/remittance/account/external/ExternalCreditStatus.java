package com.remittance.account.external;

/** 상대 은행이 내린 판정. 우리가 정하는 것이 아니라 받아 적는 것이다. */
public enum ExternalCreditStatus {
	/** 들어갔다. */
	ACCEPTED,
	/** 업무적 거절. 다시 보내도 결과가 같다 — 보상으로 넘어가야 한다. */
	REJECTED,
	/** 그런 거래가 없다. 조회했을 때만 나온다 — 보낸 적이 없거나 못 받았다는 뜻이다. */
	NOT_FOUND
}
