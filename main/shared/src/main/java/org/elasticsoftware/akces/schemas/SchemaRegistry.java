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

package org.elasticsoftware.akces.schemas;

import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import org.elasticsoftware.akces.schemas.JsonSchema;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.SchemaType;
import tools.jackson.databind.node.ArrayNode;

import java.util.Map;

public interface SchemaRegistry {
    static JsonSchema generateJsonSchema(SchemaType<?> schemaType, SchemaGenerator schemaGenerator) {
        return new JsonSchema(schemaGenerator.generateSchema(schemaType.typeClass()).toString(), schemaType.version());
    }

    static SchemaGenerator createJsonSchemaGenerator() {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_7,
                OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS,
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonSchemaModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        // we need to override the default behavior of the generator to write BigDecimal as type = number
        configBuilder.forTypesInGeneral().withTypeAttributeOverride((collectedTypeAttributes, scope, context) -> {
            if (scope.getType().getTypeName().equals("java.math.BigDecimal")) {
                var typeNode = collectedTypeAttributes.get("type");
                if (typeNode.isArray()) {
                    ((ArrayNode) typeNode).set(0, "string");
                } else
                    collectedTypeAttributes.put("type", "string");
            }
        });
        return new SchemaGenerator(configBuilder.build());
    }

    JsonSchema validate(CommandType<?> commandType) throws SchemaException;

    JsonSchema validate(DomainEventType<?> domainEventType) throws SchemaException;

    JsonSchema registerAndValidate(SchemaType<?> schemaType, boolean forceRegisterOnIncompatibleSchema) throws SchemaException;

    JsonSchema generateJsonSchema(SchemaType<?> schemaType);
}
