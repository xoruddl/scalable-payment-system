package com.remittance.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Config Server가 <b>Git에 커밋된 설정을 실제로 낸다</b> (Phase 4).
 *
 * <h2>왜 임시 저장소를 만들어 쓰나</h2>
 * 이 저장소 자신을 읽게 두면 테스트가 <b>지금 어느 브랜치에 무엇이 커밋돼 있는지</b>에 의존한다.
 * 새 설정 파일을 추가하는 PR에서는 그 파일이 아직 기본 브랜치에 없으므로 테스트가 red가 되고,
 * 그건 코드가 틀려서가 아니다. 그래서 <b>테스트가 자기 저장소를 만들어</b> 커밋해두고 물어본다.
 *
 * <h2>여기서 거는 계약</h2>
 * <ol>
 *   <li>Git 백엔드로 <b>뜬다</b> — Spring Cloud 릴리스 트레인이 Boot 4.1과 붙는지가 여기서 판별된다</li>
 *   <li>설정을 <b>실제로 낸다</b></li>
 *   <li>{@code file:} URI일 때는 <b>작업 트리</b>를 낸다 — 아래 두 번째 테스트. 예상과 반대였다</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfigServerApplicationTests {

	@TempDir
	static Path repo;

	@Autowired
	private MockMvc mockMvc;

	@BeforeAll
	static void 설정을_담은_저장소를_하나_만든다() throws Exception {
		Files.createDirectories(repo.resolve("config"));
		Files.writeString(repo.resolve("config/application.yml"),
				"remittance:\n  shared-marker: from-git\n");
		git("init", "--initial-branch=develop");
		git("config", "user.email", "test@remittance.local");
		git("config", "user.name", "test");
		git("add", ".");
		git("commit", "-m", "설정 한 줄");
	}

	/**
	 * ⚠️ <b>{@code toRealPath()}가 꼭 필요하다.</b> macOS의 임시 디렉터리는 {@code /var/...}인데
	 * {@code /var}는 {@code /private/var}로 가는 심볼릭 링크라, JGit이
	 * "Path component must not be a symbolic link"로 거부한다. 리눅스 CI에서는 안 나는 문제라
	 * <b>여기서 안 풀면 로컬에서만 red</b>가 된다.
	 */
	@DynamicPropertySource
	static void 저장소를_임시_저장소로_바꾼다(DynamicPropertyRegistry registry) {
		registry.add("spring.cloud.config.server.git.uri", () -> "file://" + realRepo());
		registry.add("spring.cloud.config.server.git.basedir",
				() -> realRepo().resolveSibling("remittance-config-clone").toString());
	}

	private static Path realRepo() {
		try {
			return repo.toRealPath();
		} catch (IOException e) {
			throw new IllegalStateException("임시 저장소 경로를 못 읽었다", e);
		}
	}

	@Test
	void 커밋된_설정을_낸다() throws Exception {
		String body = mockMvc.perform(get("/application/default"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.as("서비스가 이걸 받아 자기 설정에 합친다")
				.contains("remittance.shared-marker", "from-git");
	}

	/**
	 * <b>로컬 저장소를 가리키면 커밋 전 변경도 나간다</b> — 처음 예상과 반대였다.
	 *
	 * <p>Spring Cloud Config의 Git 백엔드는 {@code file:} URI를 만나면 <b>clone하지 않고
	 * 작업 트리를 그대로 읽는다.</b> 그래서 label(브랜치)로 시점을 고르는 의미가 약해진다 —
	 * 원격 URI일 때만 "그 브랜치에 커밋된 것"이 나온다.
	 *
	 * <p><b>이 차이를 모르면 두 번 속는다.</b> 개발할 때는 "고쳤는데 왜 반영이 되지?"로,
	 * 운영에서는 "커밋 안 했는데 왜 반영이 됐지?"로. 그래서 테스트로 못 박아둔다.
	 */
	@Test
	void 로컬_저장소를_가리키면_커밋하지_않은_변경도_낸다() throws Exception {
		Files.writeString(repo.resolve("config/application.yml"),
				"remittance:\n  shared-marker: not-committed\n");

		String body = mockMvc.perform(get("/application/default"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.as("file: URI는 clone하지 않고 작업 트리를 읽는다 — 원격 URI라면 반대다")
				.contains("not-committed")
				.doesNotContain("from-git");
	}

	private static void git(String... args) throws IOException, InterruptedException {
		String[] command = new String[args.length + 1];
		command[0] = "git";
		System.arraycopy(args, 0, command, 1, args.length);
		Process process = new ProcessBuilder(command)
				.directory(repo.toFile())
				.redirectErrorStream(true)
				.start();
		if (process.waitFor() != 0) {
			throw new IllegalStateException("git " + String.join(" ", args) + " 실패: "
					+ new String(process.getInputStream().readAllBytes()));
		}
	}
}
