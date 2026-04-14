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

package org.elasticsoftware.akces.agentic.embabel;

import com.embabel.agent.api.common.Asyncer;
import com.embabel.agent.core.support.LlmInteraction;
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver;
import com.embabel.agent.spi.ToolDecorator;
import com.embabel.agent.spi.loop.ToolLoopFactory;
import com.embabel.agent.spi.support.LlmDataBindingProperties;
import com.embabel.agent.spi.support.LlmOperationsPromptsProperties;
import com.embabel.agent.spi.support.OutputConverter;
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations;
import com.embabel.agent.spi.support.springai.ExceptionWrappingConverter;
import com.embabel.agent.spi.support.springai.SuppressThinkingConverter;
import com.embabel.agent.spi.support.springai.SuppressThinkingConverterKt;
import com.embabel.agent.spi.support.springai.WithExampleConverter;
import com.embabel.agent.spi.validation.ValidationPromptGenerator;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.textio.template.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Validator;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Extension of Embabel's {@link ChatClientLlmOperations} that replaces the Jackson 2 /
 * victools 4.x output converter with a Jackson 3 / victools 5.x compatible
 * {@link Jackson3OutputConverter}.
 *
 * <p>This is needed because Akces uses victools 5.x (which depends on Jackson 3 /
 * {@code tools.jackson}), while Embabel's built-in
 * {@code FilteringJacksonOutputConverter} is compiled against victools 4.x
 * (Jackson 2 / {@code com.fasterxml.jackson}). At runtime, victools 5.x's
 * {@code SchemaGenerator.generateSchema()} returns
 * {@code tools.jackson.databind.node.ObjectNode} instead of the expected
 * {@code com.fasterxml.jackson.databind.node.ObjectNode}, causing a
 * {@link NoSuchMethodError}.
 *
 * <p>This class overrides {@link #createOutputConverter(Class, LlmInteraction)} to
 * use {@link Jackson3OutputConverter} which generates the JSON Schema using victools 5.x
 * and Jackson 3, while still using Jackson 2 for LLM response deserialization.
 *
 * @see Jackson3OutputConverter
 */
@Service
@Primary
public class Jackson3ChatClientLlmOperations extends ChatClientLlmOperations {

    public Jackson3ChatClientLlmOperations(
            ModelProvider modelProvider,
            ToolDecorator toolDecorator,
            Validator validator,
            ValidationPromptGenerator validationPromptGenerator,
            TemplateRenderer templateRenderer,
            LlmDataBindingProperties dataBindingProperties,
            LlmOperationsPromptsProperties llmOperationsPromptsProperties,
            ApplicationContext applicationContext,
            AutoLlmSelectionCriteriaResolver autoLlmSelectionCriteriaResolver,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            List<ChatClientCustomizer> customizers,
            Asyncer asyncer,
            ToolLoopFactory toolLoopFactory) {
        super(modelProvider, toolDecorator, validator, validationPromptGenerator, templateRenderer,
                dataBindingProperties, llmOperationsPromptsProperties, applicationContext,
                autoLlmSelectionCriteriaResolver, objectMapper, observationRegistry,
                customizers, asyncer, toolLoopFactory);
    }

    @Override
    protected <O> OutputConverter<O> createOutputConverter(Class<O> outputClass, LlmInteraction interaction) {
        Jackson3OutputConverter<O> jackson3Converter = new Jackson3OutputConverter<>(
                outputClass,
                getObjectMapper$embabel_agent_api(),
                interaction.getFieldFilter());

        // Wrap in the same decorator chain as the original:
        // SuppressThinking → WithExample → ExceptionWrapping → OutputConverter adapter
        @SuppressWarnings("unchecked")
        StructuredOutputConverter<O> suppressThinking = new SuppressThinkingConverter<>(
                (StructuredOutputConverter<O>) jackson3Converter,
                List.of(SuppressThinkingConverterKt.getFindMarkupThinkBlock(),
                        SuppressThinkingConverterKt.getFindPrefixThinkBlock()));
        StructuredOutputConverter<O> withExample = new WithExampleConverter<>(
                suppressThinking, outputClass, false,
                shouldGenerateExamples(interaction));
        ExceptionWrappingConverter<O> exceptionWrapping = new ExceptionWrappingConverter<>(
                outputClass, withExample);

        // Adapt StructuredOutputConverter to Embabel's OutputConverter interface.
        // SpringAiOutputConverterAdapter is package-private in Embabel, so we create
        // our own simple adapter.
        return new OutputConverter<>() {
            @Override
            public O convert(String text) {
                return exceptionWrapping.convert(text);
            }

            @Override
            public String getFormat() {
                return exceptionWrapping.getFormat();
            }
        };
    }
}
