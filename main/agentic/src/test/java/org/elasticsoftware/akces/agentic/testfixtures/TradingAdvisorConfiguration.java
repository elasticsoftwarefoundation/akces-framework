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

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Test configuration for the TradingAdvisor integration tests.
 *
 * <p>Registers the Embabel {@link LlmService} bean that wraps the Spring AI
 * {@link AnthropicChatModel} so that Embabel's {@code ConfigurableModelProvider}
 * can resolve the default LLM by name. This is normally provided by
 * {@code embabel-agent-starter-anthropic}, which is incompatible with the
 * Spring AI version used by this project.
 */
@Configuration
@ComponentScan(basePackages = {
        "org.elasticsoftware.akces.agentic.testfixtures",
})
public class TradingAdvisorConfiguration {

    @Bean
    public LlmService<?> anthropicLlmService(AnthropicChatModel chatModel) {
        return new SpringAiLlmService("claude-sonnet-4-6", "Anthropic", chatModel);
    }
}
