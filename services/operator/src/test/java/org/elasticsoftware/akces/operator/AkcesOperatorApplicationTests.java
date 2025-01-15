/*
 * Copyright 2022 - 2025 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.operator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.springboot.starter.test.EnableMockOperator;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.elasticsoftware.akces.operator.aggregate.Aggregate;
import org.elasticsoftware.akces.operator.aggregate.AggregateReconciler;
import org.elasticsoftware.akces.operator.aggregate.AggregateSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsoftware.akces.operator.utils.KafkaTopicUtils.createCompactedTopic;
import static org.elasticsoftware.akces.operator.utils.KafkaTopicUtils.createTopic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.UseMainMethod.WHEN_AVAILABLE;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, useMainMethod = WHEN_AVAILABLE)
@ContextConfiguration(initializers = AkcesOperatorApplicationTests.KafkaInitializer.class)
@EnableMockOperator(crdPaths = {"classpath:META-INF/fabric8/aggregates.akces.elasticsoftwarefoundation.org-v1.yml"})
@Testcontainers
@DirtiesContext
class AkcesOperatorApplicationTests {
	private static final String CONFLUENT_PLATFORM_VERSION = "7.8.0";

	private static final Network network = Network.newNetwork();

	@Container
	private static final KafkaContainer kafka =
			new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:"+CONFLUENT_PLATFORM_VERSION))
					.withKraft()
					.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
					.withNetwork(network)
					.withNetworkAliases("kafka");

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private AggregateReconciler aggregateReconciler;
    @Autowired
    private KafkaAdmin kafkaAdmin;

	public static class KafkaInitializer
			implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext applicationContext) {
			// initialize kafka topics
			KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
			kafkaAdmin.createOrModifyTopics(
					createCompactedTopic("Akces-Control", 3),
					createTopic("Akces-CommandResponses", 3, 604800000L),
					createCompactedTopic("Akces-GDPRKeys", 3)
			);
			//prepareExternalServices(kafka.getBootstrapServers());
			TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
					applicationContext,
					"spring.kafka.enabled=true",
					"spring.kafka.bootstrap-servers="+kafka.getBootstrapServers()
			);
		}
	}


	@Test
	void contextLoads() {
		assertThat(restTemplate).isNotNull();
		assertThat(aggregateReconciler).isNotNull();
	}

	@Test
	void healthReadinessEndpointShouldBeEnabled() throws Exception {
		assertThat(this.restTemplate.getForObject("http://localhost:" + port + "/actuator/health/readiness",
				String.class)).contains("{\"status\":\"UP\"}");
	}

	@Test
	void healthLivenessEndpointShouldBeEnabled() throws Exception {
		assertThat(this.restTemplate.getForObject("http://localhost:" + port + "/actuator/health/liveness",
				String.class)).contains("{\"status\":\"UP\"}");
	}

	@Test
	void testReconciliation() throws Exception {
		Aggregate aggregate = new Aggregate();
		aggregate.setMetadata(new ObjectMetaBuilder()
				.withName("test-aggregate")
				.withNamespace("akces")
				.build());
		aggregate.setSpec(new AggregateSpec());
		aggregate.getSpec().setReplicas(3);
		aggregate.getSpec().setImage("test-image");
		aggregate.getSpec().setAggregateNames(List.of("Account", "OrderProcessManager", "Wallet"));

		Context<Aggregate> mockContext = mock(Context.class);
		when(mockContext.getSecondaryResource(StatefulSet.class)).thenReturn(Optional.empty());
		UpdateControl<Aggregate> updateControl = aggregateReconciler.reconcile(aggregate, mockContext);

		Map<String, TopicDescription> reconciledTopics = kafkaAdmin.describeTopics("Account-DomainEvents", "Account-Commands", "Account-AggregateState",
				"OrderProcessManager-DomainEvents", "OrderProcessManager-Commands", "OrderProcessManager-AggregateState",
				"Wallet-DomainEvents", "Wallet-Commands", "Wallet-AggregateState");

		assertThat(reconciledTopics).hasSize(9);
		assertThat(reconciledTopics.get("Account-DomainEvents").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("Account-Commands").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("Account-AggregateState").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("OrderProcessManager-DomainEvents").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("OrderProcessManager-Commands").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("OrderProcessManager-AggregateState").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("Wallet-DomainEvents").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("Wallet-Commands").partitions().size()).isEqualTo(3);
		assertThat(reconciledTopics.get("Wallet-AggregateState").partitions().size()).isEqualTo(3);



	}

}
