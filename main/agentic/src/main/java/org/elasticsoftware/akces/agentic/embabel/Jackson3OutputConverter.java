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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.StructuredOutputConverter;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.function.Predicate;

/**
 * A {@link StructuredOutputConverter} that uses Jackson 3 ({@code tools.jackson}) and
 * victools 5.x for JSON Schema generation, while using Jackson 2
 * ({@code com.fasterxml.jackson}) for JSON deserialization of LLM responses.
 *
 * <p>This converter replaces Embabel's {@code JacksonOutputConverter} which is compiled
 * against victools 4.x / Jackson 2. When victools 5.x is on the classpath (as in Akces),
 * the original converter fails with {@link NoSuchMethodError} because
 * {@code SchemaGenerator.generateSchema()} now returns
 * {@code tools.jackson.databind.node.ObjectNode} instead of
 * {@code com.fasterxml.jackson.databind.node.ObjectNode}.
 *
 * @param <T> the target type for LLM output conversion
 */
public class Jackson3OutputConverter<T> implements StructuredOutputConverter<T> {

    private static final String FORMAT_TEMPLATE = """
            Your response should be in JSON format.
            Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
            Do not include markdown code blocks in your response.
            Remove the ```json markdown from the output.
            Here is the JSON Schema instance your output must adhere to:
            ```%s```
            """;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Type type;
    private final ObjectMapper objectMapper;
    private final ObjectMapper lenientMapper;
    private final Predicate<Field> fieldFilter;
    private final String jsonSchema;

    /**
     * Creates a new converter for the given class type.
     *
     * @param clazz        the target class for deserialization
     * @param objectMapper the Jackson 2 ObjectMapper used for JSON deserialization
     * @param fieldFilter  predicate to filter which fields appear in the JSON Schema;
     *                     fields for which the predicate returns {@code false} are excluded
     */
    public Jackson3OutputConverter(Class<T> clazz, ObjectMapper objectMapper, Predicate<Field> fieldFilter) {
        this.type = clazz;
        this.objectMapper = objectMapper;
        this.fieldFilter = fieldFilter;
        this.lenientMapper = createLenientMapper(objectMapper);
        this.jsonSchema = generateJsonSchema();
    }

    /**
     * Creates a new converter for the given class type without field filtering.
     *
     * @param clazz        the target class for deserialization
     * @param objectMapper the Jackson 2 ObjectMapper used for JSON deserialization
     */
    public Jackson3OutputConverter(Class<T> clazz, ObjectMapper objectMapper) {
        this(clazz, objectMapper, field -> true);
    }

    @Override
    public T convert(String text) {
        String unwrapped = unwrapJson(text);
        try {
            return lenientMapper.readValue(unwrapped, lenientMapper.constructType(type));
        } catch (JsonProcessingException e) {
            logger.error(org.springframework.ai.util.LoggingMarkers.SENSITIVE_DATA_MARKER,
                    "Could not parse the given text to the desired target type: \"{}\" into {}", unwrapped, type);
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getFormat() {
        return FORMAT_TEMPLATE.formatted(jsonSchema);
    }

    private String generateJsonSchema() {
        try {
            SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                    SchemaVersion.DRAFT_2020_12,
                    OptionPreset.PLAIN_JSON);
            configBuilder.with(new JacksonSchemaModule(
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.RESPECT_JSONPROPERTY_ORDER));
            configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);

            // Apply field filter if present
            if (fieldFilter != null) {
                configBuilder.forFields().withIgnoreCheck(fieldScope -> {
                    java.lang.reflect.Member rawMember = fieldScope.getMember().getRawMember();
                    if (rawMember instanceof Field field) {
                        return !fieldFilter.test(field);
                    }
                    return false;
                });
            }

            SchemaGenerator generator = new SchemaGenerator(configBuilder.build());
            tools.jackson.databind.node.ObjectNode schemaNode = generator.generateSchema(type);

            // Pretty print using Jackson 3
            tools.jackson.databind.ObjectMapper jackson3Mapper = new tools.jackson.databind.json.JsonMapper();
            return jackson3Mapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(schemaNode);
        } catch (Exception e) {
            logger.warn("Could not pretty print json schema for {}", type, e);
            return "";
        }
    }

    private static ObjectMapper createLenientMapper(ObjectMapper original) {
        ObjectMapper lenient = original.copy();
        lenient.enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());
        lenient.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature());
        lenient.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
        lenient.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return lenient;
    }

    private String unwrapJson(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed = trimmed.replaceFirst("^```json", "");
            trimmed = trimmed.replaceFirst("^```", "");
            trimmed = trimmed.replaceFirst("```$", "");
            trimmed = trimmed.strip();
        }
        return fixMalformedEscapedQuotes(trimmed);
    }

    private String fixMalformedEscapedQuotes(String text) {
        return text.replace("\\\"", "\"");
    }
}
