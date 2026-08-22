package com.remittance.account.support;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빌드 결과물이 <b>자기가 어느 커밋인지</b> 답할 수 있어야 한다.
 *
 * <p>아티팩트 버전은 {@code 0.0.1-SNAPSHOT}으로 고정이라 커밋을 구분하지 못하고,
 * 컨테이너 이미지 태그는 배포한 사람만 압니다. 그래서 <b>실행 중인 프로세스에 직접 물어볼 수
 * 있어야</b> 합니다 — {@code GET /actuator/info}가 이 파일을 읽어 답합니다.
 *
 * <p>이게 없으면 장애 대응 중에 "지금 떠 있는 게 어느 코드냐"를 아무도 확답하지 못합니다.
 * Phase 7에서 이미지를 굽고 Phase 8에서 배포하기 시작하면 그 순간부터 필요합니다.
 *
 * <p>Docker가 필요 없어 {@code ./gradlew unitTest}에 포함됩니다. 루트 {@code build.gradle}의
 * {@code bootBuildInfo} 설정이 사라지면 이 테스트가 빨개집니다.
 */
class BuildInfoTest {

	private Properties buildInfo() throws Exception {
		try (InputStream in = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
			assertThat(in)
					.as("build-info.properties가 없다 — 루트 build.gradle의 bootBuildInfo 설정을 확인하라")
					.isNotNull();
			Properties properties = new Properties();
			properties.load(in);
			return properties;
		}
	}

	@Test
	void 빌드_결과물이_자기_커밋을_알고_있다() throws Exception {
		assertThat(buildInfo().getProperty("build.commit"))
				.as("커밋을 모르면 '지금 떠 있는 게 어느 코드냐'에 답할 수 없다")
				.isNotBlank()
				// git이 없는 환경에서는 'unknown'으로 떨어지도록 해뒀지만, 저장소 안에서 빌드하면
				// 반드시 실제 값이어야 한다. 조용히 unknown이 되는 걸 잡는다.
				.isNotEqualTo("unknown")
				.matches("[0-9a-f]{12}");
	}

	@Test
	void 어느_브랜치에서_나온_빌드인지도_남는다() throws Exception {
		assertThat(buildInfo().getProperty("build.branch"))
				.as("CI는 detached HEAD로 체크아웃해서 'HEAD'가 나올 수 있다. 비어 있지만 않으면 된다")
				.isNotBlank();
	}
}
