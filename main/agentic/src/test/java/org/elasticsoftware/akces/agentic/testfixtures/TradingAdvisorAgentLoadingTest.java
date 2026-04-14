/*
 * Copyright 2022 - 2026 The Original Authors
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

package org.elasticsoftware.akces.agentic.testfixtures;

import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.support.AgentMetadataReader;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentScope;
import com.embabel.agent.core.deployment.AgentScanningProperties;
import com.embabel.agent.spi.support.AgentScanningPostProcessorDelegate;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests that Embabel's {@link AgentMetadataReader} can successfully create agent metadata
 * from the combined {@link TradingAdvisor} class, which carries both
 * {@code @AgenticAggregateInfo} and {@code @Agent} annotations.
 *
 * <p>This test reproduces the issue where the Embabel runtime could not load
 * the TradingAdvisor agent after the aggregate and agent classes were merged.
 */
class TradingAdvisorAgentLoadingTest {

    @Test
    @DisplayName("AgentMetadataReader should create agent metadata from combined TradingAdvisor class")
    void agentMetadataReaderShouldLoadCombinedClass() {
        AgentMetadataReader reader = new AgentMetadataReader();
        TradingAdvisor tradingAdvisor = new TradingAdvisor();

        // This is the call that Embabel's DelegatingAgentScanningBeanPostProcessor
        // makes during bean post-processing. If it returns null, the agent is NOT
        // deployed to the AgentPlatform, which is the reported issue.
        AgentScope agentScope = reader.createAgentMetadata(tradingAdvisor);

        assertThat(agentScope)
                .as("AgentMetadataReader must produce a non-null AgentScope for the combined class")
                .isNotNull();
        assertThat(agentScope.getName())
                .as("Agent name should match the @Agent(name=...) value")
                .isEqualTo(TradingAdvisor.AGGREGATE_ID);
    }

    @Test
    @DisplayName("AgentMetadataReader should find the @Action method on the combined class")
    void agentMetadataReaderShouldFindActionMethod() {
        AgentMetadataReader reader = new AgentMetadataReader();
        TradingAdvisor tradingAdvisor = new TradingAdvisor();

        AgentScope agentScope = reader.createAgentMetadata(tradingAdvisor);
        assertThat(agentScope).isNotNull();

        assertThat(agentScope.getActions())
                .as("Agent should have at least one action from @Action-annotated method")
                .isNotEmpty();
    }

    @Test
    @DisplayName("AgentMetadataReader should find goals from @AchievesGoal on the combined class")
    void agentMetadataReaderShouldFindGoals() {
        AgentMetadataReader reader = new AgentMetadataReader();
        TradingAdvisor tradingAdvisor = new TradingAdvisor();

        AgentScope agentScope = reader.createAgentMetadata(tradingAdvisor);
        assertThat(agentScope).isNotNull();

        assertThat(agentScope.getGoals())
                .as("Agent should have at least one goal from @AchievesGoal annotation")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Both @AgenticAggregateInfo and @Agent annotations should be discoverable on the combined class")
    void bothAnnotationsShouldBeDiscoverable() {
        Class<?> clazz = TradingAdvisor.class;

        // Direct annotation check — used by Embabel's AgenticInfo
        Agent agentAnnotation = clazz.getAnnotation(Agent.class);
        AgenticAggregateInfo aggregateAnnotation = clazz.getAnnotation(AgenticAggregateInfo.class);

        assertThat(agentAnnotation)
                .as("@Agent annotation should be directly present on TradingAdvisor")
                .isNotNull();
        assertThat(aggregateAnnotation)
                .as("@AgenticAggregateInfo annotation should be directly present on TradingAdvisor")
                .isNotNull();

        assertThat(agentAnnotation.name()).isEqualTo(TradingAdvisor.AGGREGATE_ID);
        assertThat(aggregateAnnotation.value()).isEqualTo(TradingAdvisor.AGGREGATE_ID);
    }

    @Test
    @DisplayName("Spring should not throw AnnotationConfigurationException for dual @Component meta-annotations")
    void springAnnotationMergingShouldNotConflict() {
        // Both @AgenticAggregateInfo and @Agent are @Component meta-annotations.
        // Verify Spring's MergedAnnotations can process the class without error.
        MergedAnnotations mergedAnnotations = MergedAnnotations.from(TradingAdvisor.class);

        MergedAnnotation<Component> component = mergedAnnotations.get(Component.class);
        assertThat(component.isPresent())
                .as("@Component should be resolvable from merged annotations")
                .isTrue();
    }

    @Test
    @DisplayName("Spring AnnotationConfigApplicationContext should register the combined bean without error")
    void springContextShouldRegisterCombinedBean() {
        // This tests that Spring's component scanning can handle the dual
        // @Component meta-annotations without throwing AnnotationConfigurationException
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TradingAdvisor.class);
            ctx.refresh();

            TradingAdvisor bean = ctx.getBean(TradingAdvisor.class);
            assertThat(bean).isNotNull();

            // The bean should be registered under the name from @AgenticAggregateInfo.value()
            String[] beanNames = ctx.getBeanNamesForType(TradingAdvisor.class);
            assertThat(beanNames)
                    .as("TradingAdvisor bean should be registered in Spring context")
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("AgentScanningPostProcessorDelegate should deploy the combined agent to AgentPlatform")
    void agentScanningDelegateShouldDeployCombinedAgent() {
        // This test simulates the full Embabel agent scanning pipeline:
        // AgentScanningPostProcessorDelegate → AgentMetadataReader → AgentPlatform.deploy
        AgentMetadataReader reader = new AgentMetadataReader();
        AgentPlatform mockPlatform = mock(AgentPlatform.class);
        AgentScanningProperties properties = new AgentScanningProperties(); // defaults: annotation=true, bean=true

        AgentScanningPostProcessorDelegate delegate =
                new AgentScanningPostProcessorDelegate(reader, mockPlatform, properties);

        TradingAdvisor tradingAdvisor = new TradingAdvisor();

        // This is exactly what DelegatingAgentScanningBeanPostProcessor calls.
        // If it returns null, the bean is destroyed in the container!
        Object result = delegate.postProcessAfterInitialization(tradingAdvisor, "TradingAdvisor");

        assertThat(result)
                .as("postProcessAfterInitialization must return the bean (not null) to keep it in the container")
                .isNotNull()
                .isSameAs(tradingAdvisor);

        // Verify the agent was deployed to the platform
        verify(mockPlatform).deploy(any(AgentScope.class));
    }
}
