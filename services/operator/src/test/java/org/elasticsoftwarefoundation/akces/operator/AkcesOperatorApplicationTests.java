package org.elasticsoftwarefoundation.akces.operator;

import io.javaoperatorsdk.operator.springboot.starter.test.EnableMockOperator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnableMockOperator(crdPaths = {"classpath:META-INF/fabric8/aggregates.akces.elasticsoftwarefoundation.org-v1.yml"})
class AkcesOperatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
