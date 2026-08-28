package com.remittance.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * 여섯 서비스가 공유하는 설정을 한 곳에서 낸다 (Phase 4).
 *
 * <h2>왜 Git 백엔드인가</h2>
 * 설정도 <b>언제 누가 왜 바꿨는지</b>가 남아야 한다. 파일시스템(`native`)으로 두면 그 이력이
 * 통째로 사라지고, 사고가 났을 때 "그때 설정이 뭐였나"에 답할 수 없다.
 * <b>이력이 Config Server의 값어치</b>라 그걸 버리면 쓸 이유가 없다.
 *
 * <p>저장소는 <b>이 저장소 자신</b>이고 설정은 `config/` 아래에 있다. 별도 저장소로 빼지 않은
 * 이유는 이 프로젝트가 `build-info`로 "지금 떠 있는 게 어느 커밋인가"에 답하도록 만들어져
 * 있기 때문이다 — 설정이 다른 저장소에 있으면 <b>그 답이 반쪽</b>이 된다.
 * (매니페스트 저장소 분리는 Phase 8의 결정이고, 그때 함께 옮기는 편이 낫다.)
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConfigServerApplication.class, args);
	}

}
