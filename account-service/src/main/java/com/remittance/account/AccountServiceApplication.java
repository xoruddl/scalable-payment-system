package com.remittance.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling}은 Outbox 보관 기간 정리 · 외부 은행 조회 · 조각 수 갱신에 필요하다.
 * 릴레이는 스케줄러가 아니라 전용 스레드로 돈다({@code OutboxRelayLoop}, D-007).
 */
@SpringBootApplication
@EnableScheduling
public class AccountServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AccountServiceApplication.class, args);
	}

}
