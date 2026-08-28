package com.remittance.account.web;

import com.remittance.account.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 문서를 <b>손으로 쓰지 않고 코드에서 낸다</b> (Phase 4).
 *
 * <h2>왜 이 테스트가 있나</h2>
 * 손으로 쓴 `docs/openapi.yaml`은 <b>이미 코드와 어긋나 있었다.</b> 문서는 코드가 바뀔 때
 * 같이 바뀌지 않으면 <b>틀린 문서</b>가 되고, 틀린 문서는 없느니만 못하다 —
 * 읽는 사람이 그걸 믿고 호출하기 때문이다.
 *
 * <p>그래서 거는 계약은 하나다. <b>뜬 서비스가 자기 입으로 자기 계약을 말한다.</b>
 * 생성되는지만 보면 "생성은 되는데 낡은 것"과 구분이 안 되므로,
 * <b>최근에 추가한 경로까지 따라오는지</b>를 함께 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	private String spec(String path) throws Exception {
		return mockMvc.perform(get(path))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	void 뜬_서비스가_자기_계약을_말한다() throws Exception {
		assertThat(spec("/v3/api-docs")).contains("\"/accounts\"", "\"/accounts/{accountId}\"");
	}

	@Test
	void 오늘_추가한_경로도_따라온다() throws Exception {
		// 2026-08-28에 추가한 대사용 조회다. 손으로 쓴 문서였다면 여기 없었을 것이다.
		assertThat(spec("/v3/api-docs"))
				.as("코드에서 나오는 문서라면 코드에 있는 경로가 빠질 수 없다")
				.contains("/internal/reconciliation/unknown-external-credits");
	}

	/**
	 * <b>이게 이 PR에서 가장 중요한 계약이다.</b> Gateway가 공개 그룹을 그대로 노출할 것이므로,
	 * 여기 `/internal/*`이 섞여 들어가면 <b>잔액을 고치는 문이 공개 API로</b> 나간다.
	 */
	@Test
	void 공개_문서에는_내부_경로가_없다() throws Exception {
		assertThat(spec("/v3/api-docs/public"))
				.contains("\"/accounts\"")
				.doesNotContain("/internal/");
	}

	@Test
	void 내부_문서에는_내부_경로만_있다() throws Exception {
		assertThat(spec("/v3/api-docs/internal"))
				.contains("/internal/reconciliation/balances")
				.doesNotContain("\"/accounts\"");
	}
}
