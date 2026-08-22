package com.remittance.transfer.web.dto;

import com.remittance.transfer.domain.Transfer;
import com.remittance.transfer.domain.TransferStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 종결되지 않은 채 오래 남은 송금. 정상이라면 몇 초 안에 끝나므로,
 * 한참 지나도 이 상태라는 건 <b>흐름이 어딘가에서 끊겼다</b>는 뜻이다.
 */
public record UnsettledTransferView(
		UUID transferId,
		TransferStatus status,
		Instant requestedAt
) {
	public static UnsettledTransferView from(Transfer transfer) {
		return new UnsettledTransferView(
				transfer.getTransferId(), transfer.getStatus(), transfer.getRequestedAt());
	}
}
