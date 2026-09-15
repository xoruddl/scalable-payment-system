package com.remittance.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling}은 Outbox 보관 기간 정리에 필요하다. 릴레이는 스케줄러가 아니라
 * 전용 스레드로 돈다({@code OutboxRelayLoop}, D-007).
 */
@SpringBootApplication
@EnableScheduling
public class TransferServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransferServiceApplication.class, args);
	}

}
