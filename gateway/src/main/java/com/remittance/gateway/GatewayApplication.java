package com.remittance.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 밖에서 오는 요청이 지나는 <b>단 하나의 문</b> (Phase 4).
 *
 * <p>여기서 하는 일은 셋이다 — 어디로 보낼지 정하고(라우팅), 내부 경로를 막고,
 * 누가 보냈는지 확인해 뒤로 넘긴다. 각 서비스가 여섯 번 할 일을 한 번만 한다.
 */
@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}

}
