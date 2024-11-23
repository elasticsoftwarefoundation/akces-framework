/*
 * Copyright 2022 - 2023 The Original Authors
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

package org.elasticsoftware.akcestest.schemas;


import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.SimpleParsedSchemaHolder;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.elasticsoftware.akcestest.aggregate.wallet.InvalidAmountErrorEvent;
import org.everit.json.schema.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JsonSchemaTests {
    @Test
    public void testSchemaCompatibility() throws IOException {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        JsonNode schemaV1 = generator.generateSchema(AccountCreatedEvent.class);
        JsonNode schemaV2 = generator.generateSchema(AccountCreatedEventV2.class);

        System.out.println(schemaV1.toString());
        System.out.println(schemaV2.toString());

        JsonSchema schema1 = new JsonSchema(schemaV1);
        JsonSchema schema2 = new JsonSchema(schemaV2);

        assertEquals(schema2.isCompatible(CompatibilityLevel.BACKWARD_TRANSITIVE, List.of(new SimpleParsedSchemaHolder(schema1))).size(), 0);

        schema2.validate(schema2.toJson(new AccountCreatedEvent("1", "Musk", AccountTypeV1.PREMIUM)));

        schema2.validate(schema2.toJson(new AccountCreatedEventV2("1","Musk",AccountTypeV2.PREMIUM, "Elon", "US")));

        // schema2.validate(new AccountCreatedEvent("1", null, AccountTypeV1.PREMIUM));

        //Assert.assertEquals(schema2.isBackwardCompatible(schema1).size(), 0);
    }

    @Test
    public void testNullableString() throws IOException {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        JsonNode schema = generator.generateSchema(InvalidAmountErrorEvent.class);
        JsonSchema jsonSchema = new JsonSchema(schema);

        jsonSchema.validate(jsonSchema.toJson(new InvalidAmountErrorEvent(UUID.randomUUID().toString(), "USD")));
    }

    @Test
    public void testNullForNotNullField() {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        JsonNode schema = generator.generateSchema(InvalidAmountErrorEvent.class);
        JsonSchema jsonSchema = new JsonSchema(schema);

        ValidationException exception = Assertions.assertThrows(ValidationException.class, () -> {
            jsonSchema.validate(jsonSchema.toJson(new InvalidAmountErrorEvent(UUID.randomUUID().toString(), null)));
        });

        assertEquals("#/currency", exception.getPointerToViolation());
        assertEquals("#/currency: expected type: String, found: Null", exception.getMessage());

    }
}
