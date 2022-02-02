package org.elasticsoftware.akces.schemas;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ser.std.CollectionSerializer;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

public class JsonSchemaTests {
    @Test
    public void testSchemaCompatibility() throws JsonProcessingException {
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

        Assert.assertEquals(schema2.isCompatible(CompatibilityLevel.BACKWARD_TRANSITIVE, List.of(schema1)).size(), 0);

        schema2.validate(new AccountCreatedEvent("1", "Musk", AccountTypeV1.PREMIUM));

        schema2.validate(new AccountCreatedEventV2("1","Musk",AccountTypeV2.PREMIUM, "Elon"));

        //Assert.assertEquals(schema2.isBackwardCompatible(schema1).size(), 0);
    }
}
