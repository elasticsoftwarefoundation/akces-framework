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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for {@link Jackson3OutputConverter} which uses Jackson 3 / victools 5.x
 * for JSON Schema generation while keeping Jackson 2 for LLM response deserialization.
 *
 * <p>Verifies that the converter correctly generates JSON Schema using victools 5.x
 * (avoiding the {@link NoSuchMethodError} from victools 4.x / Jackson 2 incompatibility)
 * and can parse LLM JSON responses back into Java objects.
 */
class Jackson3OutputConverterTest {

    record TestOutput(String name, int value) {}

    record NestedOutput(String id, TestOutput nested) {}

    @Test
    void shouldGenerateJsonSchemaWithVictools5() {
        var converter = new Jackson3OutputConverter<>(TestOutput.class, new ObjectMapper());
        String format = converter.getFormat();

        assertThat(format).contains("JSON Schema");
        assertThat(format).contains("\"name\"");
        assertThat(format).contains("\"value\"");
        assertThat(format).contains("\"type\"");
    }

    @Test
    void shouldConvertJsonStringToObject() {
        var converter = new Jackson3OutputConverter<>(TestOutput.class, new ObjectMapper());
        TestOutput result = converter.convert("{\"name\": \"test\", \"value\": 42}");

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("test");
        assertThat(result.value()).isEqualTo(42);
    }

    @Test
    void shouldHandleMarkdownCodeBlocks() {
        var converter = new Jackson3OutputConverter<>(TestOutput.class, new ObjectMapper());
        TestOutput result = converter.convert("```json\n{\"name\": \"test\", \"value\": 42}\n```");

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("test");
        assertThat(result.value()).isEqualTo(42);
    }

    @Test
    void shouldHandleNestedObjects() {
        var converter = new Jackson3OutputConverter<>(NestedOutput.class, new ObjectMapper());
        String format = converter.getFormat();

        assertThat(format).contains("\"id\"");
        assertThat(format).contains("\"nested\"");
    }

    @Test
    void shouldFilterFieldsFromSchema() {
        var converter = new Jackson3OutputConverter<>(TestOutput.class, new ObjectMapper(),
                field -> !field.getName().equals("value"));
        String format = converter.getFormat();

        assertThat(format).contains("\"name\"");
        assertThat(format).doesNotContain("\"value\"");
    }

    @Test
    void schemaGenerationShouldNotThrowNoSuchMethodError() {
        // This is the core test: verifying that victools 5.x schema generation
        // does NOT throw the NoSuchMethodError that occurs with the original
        // JacksonOutputConverter compiled against victools 4.x
        assertThatNoException().isThrownBy(() ->
                new Jackson3OutputConverter<>(TestOutput.class, new ObjectMapper()).getFormat());
    }

    @Test
    void shouldHandleLenientJsonParsing() {
        var converter = new Jackson3OutputConverter<>(TestOutput.class, new ObjectMapper());

        // Test with trailing comma (lenient parsing)
        TestOutput result = converter.convert("{\"name\": \"test\", \"value\": 42,}");
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("test");
        assertThat(result.value()).isEqualTo(42);
    }
}
