package org.elasticsoftwarefoundation.akces.operator;

import io.javaoperatorsdk.operator.springboot.starter.test.EnableMockOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableMockOperator(crdPaths = {"classpath:META-INF/fabric8/aggregates.akces.elasticsoftwarefoundation.org-v1.yml"})
class AkcesOperatorApplicationTests {
	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;


	@Test
	void contextLoads() {
		assertThat(restTemplate).isNotNull();
	}

	@Test
	void healthEndpointShouldBeEnabled() throws Exception {
		assertThat(this.restTemplate.getForObject("http://localhost:" + port + "/actuator/health",
				String.class)).contains("{\"status\":\"UP\"}");
	}

}
