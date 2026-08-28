package com.remittance.externalbank

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * 상대 은행 — **우리 조직이 아닌 것**을 흉내 낸다.
 *
 * <p>지금까지 송금의 입금과 출금은 같은 `account-service`, 같은 DB에서 일어났다.
 * 그래서 "받는 계좌"는 사실상 같은 트랜잭션 옆자리였고, 이 시스템에는
 * **"입금 요청을 보냈는데 타임아웃 — 들어갔나 안 들어갔나"** 라는 상태가 아예 없었다.
 *
 * <p>이 서비스는 그 상태를 만들어내기 위해 있다. 그래서 **일부러 나쁘게 군다** —
 * 느리게 답하고, 아예 안 답하고, 5xx를 주고, 업무적으로 거절한다.
 * 잘 응답하는 서비스를 하나 더 만들면 홉만 늘어날 뿐 배우는 게 없다.
 *
 * <p>Kotlin인 이유는 **남이 만든 시스템이기 때문**이다. 다른 조직의 시스템이 다른 언어인 것은
 * 설명이 필요 없고, 그게 MSA에서 언어를 섞는 진짜 이유다.
 * 서비스끼리 HTTP와 JSON으로만 이야기하므로 언어가 달라도 계약만 맞으면 된다.
 */
@SpringBootApplication
class ExternalBankApplication

fun main(args: Array<String>) {
	runApplication<ExternalBankApplication>(*args)
}
