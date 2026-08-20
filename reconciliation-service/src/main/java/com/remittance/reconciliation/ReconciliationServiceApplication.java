package com.remittance.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정합성 대사 서비스 (Phase 2 Step 5b).
 *
 * <p>다른 서비스들이 각자 옳게 동작해도, <b>합쳐놓고 보면 어긋날 수 있다.</b> Saga가 중간에
 * 끊기거나 이벤트가 DLT로 빠지면 계좌 잔액과 원장이 벌어지고, 송금이 종결되지 못한 채 남는다.
 * 지금까지는 그런 일이 생겨도 <b>아무도 몰랐다</b> — 사람이 직접 조회해봐야 알 수 있었다.
 *
 * <p>이 서비스는 주기적으로 세 서비스에 물어보고 어긋난 것을 찾아 기록한다.
 * <b>찾아서 알리는 데까지만 한다</b> — 고치는 건 데이터 주인의 몫이다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ReconciliationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReconciliationServiceApplication.class, args);
	}
}
