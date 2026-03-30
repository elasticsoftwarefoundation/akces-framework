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

package org.elasticsoftware.akces.codegen;

import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.InputStream;

import static org.testng.Assert.*;

/**
 * Validates that the event-modeling JSON definition files conform to the
 * augmented Akces event-model JSON schema.
 */
public class SchemaValidationTest {

    private Schema schema;

    @BeforeClass
    public void loadSchema() {
        InputStream schemaStream = getClass().getClassLoader()
                .getResourceAsStream("akces-event-model.schema.json");
        assertNotNull(schemaStream, "Schema resource not found: akces-event-model.schema.json");
        JSONObject rawSchema = new JSONObject(new JSONTokener(schemaStream));
        schema = SchemaLoader.load(rawSchema);
    }

    @Test
    public void testAccountDefinitionConformsToSchema() {
        JSONObject json = loadJson("crypto-trading-account.json");
        schema.validate(json);
    }

    @Test
    public void testWalletDefinitionConformsToSchema() {
        JSONObject json = loadJson("crypto-trading-wallet.json");
        schema.validate(json);
    }

    @Test
    public void testMissingRequiredPackageName() {
        JSONObject json = new JSONObject()
                .put("aggregateConfig", new JSONObject())
                .put("slices", new org.json.JSONArray());

        ValidationException ex = expectThrows(ValidationException.class, () -> schema.validate(json));
        assertTrue(ex.getAllMessages().stream().anyMatch(m -> m.contains("packageName")),
                "Should report missing packageName: " + ex.getAllMessages());
    }

    @Test
    public void testMissingRequiredSlices() {
        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject());

        ValidationException ex = expectThrows(ValidationException.class, () -> schema.validate(json));
        assertTrue(ex.getAllMessages().stream().anyMatch(m -> m.contains("slices")),
                "Should report missing slices: " + ex.getAllMessages());
    }

    @Test
    public void testMissingRequiredAggregateConfig() {
        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("slices", new org.json.JSONArray());

        ValidationException ex = expectThrows(ValidationException.class, () -> schema.validate(json));
        assertTrue(ex.getAllMessages().stream().anyMatch(m -> m.contains("aggregateConfig")),
                "Should report missing aggregateConfig: " + ex.getAllMessages());
    }

    @Test
    public void testAdditionalPropertiesRejected() {
        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject())
                .put("slices", new org.json.JSONArray())
                .put("unknownProperty", "value");

        assertThrows(ValidationException.class, () -> schema.validate(json));
    }

    @Test
    public void testInvalidSliceTypeRejected() {
        JSONObject slice = new JSONObject()
                .put("id", "test")
                .put("title", "Test")
                .put("sliceType", "INVALID_TYPE")
                .put("commands", new org.json.JSONArray())
                .put("events", new org.json.JSONArray())
                .put("readmodels", new org.json.JSONArray())
                .put("screens", new org.json.JSONArray())
                .put("processors", new org.json.JSONArray())
                .put("tables", new org.json.JSONArray())
                .put("specifications", new org.json.JSONArray());

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject())
                .put("slices", new org.json.JSONArray().put(slice));

        assertThrows(ValidationException.class, () -> schema.validate(json));
    }

    @Test
    public void testInvalidFieldTypeRejected() {
        JSONObject field = new JSONObject()
                .put("name", "test")
                .put("type", "InvalidType");

        JSONObject element = new JSONObject()
                .put("id", "test-cmd")
                .put("title", "Test")
                .put("type", "COMMAND")
                .put("fields", new org.json.JSONArray().put(field))
                .put("dependencies", new org.json.JSONArray());

        JSONObject slice = new JSONObject()
                .put("id", "test")
                .put("title", "Test")
                .put("sliceType", "STATE_CHANGE")
                .put("commands", new org.json.JSONArray().put(element))
                .put("events", new org.json.JSONArray())
                .put("readmodels", new org.json.JSONArray())
                .put("screens", new org.json.JSONArray())
                .put("processors", new org.json.JSONArray())
                .put("tables", new org.json.JSONArray())
                .put("specifications", new org.json.JSONArray());

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject())
                .put("slices", new org.json.JSONArray().put(slice));

        assertThrows(ValidationException.class, () -> schema.validate(json));
    }

    @Test
    public void testInvalidStateFieldTypeRejected() {
        JSONObject stateField = new JSONObject()
                .put("name", "id")
                .put("type", "NotAType");

        JSONObject aggregateConfig = new JSONObject()
                .put("stateFields", new org.json.JSONArray().put(stateField));

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject().put("Test", aggregateConfig))
                .put("slices", new org.json.JSONArray());

        assertThrows(ValidationException.class, () -> schema.validate(json));
    }

    @Test
    public void testValidMinimalDefinition() {
        JSONObject stateField = new JSONObject()
                .put("name", "id")
                .put("type", "String");

        JSONObject aggregateConfig = new JSONObject()
                .put("stateFields", new org.json.JSONArray().put(stateField));

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject().put("Test", aggregateConfig))
                .put("slices", new org.json.JSONArray());

        schema.validate(json);
    }

    @Test
    public void testStateFieldPiiDataAccepted() {
        JSONObject stateField = new JSONObject()
                .put("name", "email")
                .put("type", "String")
                .put("piiData", true)
                .put("idAttribute", false)
                .put("optional", false);

        JSONObject aggregateConfig = new JSONObject()
                .put("stateFields", new org.json.JSONArray().put(stateField));

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject().put("User", aggregateConfig))
                .put("slices", new org.json.JSONArray());

        schema.validate(json);
    }

    @Test
    public void testAggregateConfigWithAllProperties() {
        JSONObject stateField = new JSONObject()
                .put("name", "id")
                .put("type", "String")
                .put("idAttribute", true);

        JSONObject aggregateConfig = new JSONObject()
                .put("indexed", true)
                .put("indexName", "TestIndex")
                .put("generateGDPRKeyOnCreate", true)
                .put("stateVersion", 1)
                .put("stateFields", new org.json.JSONArray().put(stateField));

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject().put("Test", aggregateConfig))
                .put("slices", new org.json.JSONArray());

        schema.validate(json);
    }

    @Test
    public void testExternalEventsWithValidElementAccepted() {
        JSONObject externalEvent = new JSONObject()
                .put("id", "account-created-ext")
                .put("title", "AccountCreated")
                .put("type", "EVENT")
                .put("aggregate", "Account")
                .put("fields", new org.json.JSONArray().put(
                        new JSONObject().put("name", "userId").put("type", "String").put("idAttribute", true)))
                .put("dependencies", new org.json.JSONArray());

        JSONObject stateField = new JSONObject()
                .put("name", "userId")
                .put("type", "String")
                .put("idAttribute", true);

        JSONObject aggregateConfig = new JSONObject()
                .put("stateFields", new org.json.JSONArray().put(stateField));

        JSONObject slice = new JSONObject()
                .put("id", "create-wallet")
                .put("title", "Create Wallet")
                .put("sliceType", "STATE_CHANGE")
                .put("commands", new org.json.JSONArray())
                .put("events", new org.json.JSONArray())
                .put("external_events", new org.json.JSONArray().put(externalEvent))
                .put("readmodels", new org.json.JSONArray())
                .put("screens", new org.json.JSONArray())
                .put("processors", new org.json.JSONArray())
                .put("tables", new org.json.JSONArray())
                .put("specifications", new org.json.JSONArray());

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject().put("Wallet", aggregateConfig))
                .put("slices", new org.json.JSONArray().put(slice));

        schema.validate(json);
    }

    @Test
    public void testExternalEventsFieldOptionalInSlice() {
        JSONObject stateField = new JSONObject()
                .put("name", "userId")
                .put("type", "String");

        JSONObject aggregateConfig = new JSONObject()
                .put("stateFields", new org.json.JSONArray().put(stateField));

        JSONObject slice = new JSONObject()
                .put("id", "test")
                .put("title", "Test")
                .put("sliceType", "STATE_CHANGE")
                .put("commands", new org.json.JSONArray())
                .put("events", new org.json.JSONArray())
                .put("readmodels", new org.json.JSONArray())
                .put("screens", new org.json.JSONArray())
                .put("processors", new org.json.JSONArray())
                .put("tables", new org.json.JSONArray())
                .put("specifications", new org.json.JSONArray());

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject().put("Test", aggregateConfig))
                .put("slices", new org.json.JSONArray().put(slice));

        schema.validate(json);
    }

    @Test
    public void testExternalEventsInvalidElementRejected() {
        JSONObject invalidElement = new JSONObject()
                .put("id", "test-ext")
                .put("title", "Test")
                .put("type", "INVALID")
                .put("fields", new org.json.JSONArray())
                .put("dependencies", new org.json.JSONArray());

        JSONObject stateField = new JSONObject()
                .put("name", "id")
                .put("type", "String");

        JSONObject aggregateConfig = new JSONObject()
                .put("stateFields", new org.json.JSONArray().put(stateField));

        JSONObject slice = new JSONObject()
                .put("id", "test")
                .put("title", "Test")
                .put("sliceType", "STATE_CHANGE")
                .put("commands", new org.json.JSONArray())
                .put("events", new org.json.JSONArray())
                .put("external_events", new org.json.JSONArray().put(invalidElement))
                .put("readmodels", new org.json.JSONArray())
                .put("screens", new org.json.JSONArray())
                .put("processors", new org.json.JSONArray())
                .put("tables", new org.json.JSONArray())
                .put("specifications", new org.json.JSONArray());

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject().put("Test", aggregateConfig))
                .put("slices", new org.json.JSONArray().put(slice));

        assertThrows(ValidationException.class, () -> schema.validate(json));
    }

    @Test
    public void testInvalidElementTypeRejected() {
        JSONObject element = new JSONObject()
                .put("id", "test-cmd")
                .put("title", "Test")
                .put("type", "INVALID")
                .put("fields", new org.json.JSONArray())
                .put("dependencies", new org.json.JSONArray());

        JSONObject slice = new JSONObject()
                .put("id", "test")
                .put("title", "Test")
                .put("sliceType", "STATE_CHANGE")
                .put("commands", new org.json.JSONArray().put(element))
                .put("events", new org.json.JSONArray())
                .put("readmodels", new org.json.JSONArray())
                .put("screens", new org.json.JSONArray())
                .put("processors", new org.json.JSONArray())
                .put("tables", new org.json.JSONArray())
                .put("specifications", new org.json.JSONArray());

        JSONObject json = new JSONObject()
                .put("packageName", "com.example")
                .put("aggregateConfig", new JSONObject())
                .put("slices", new org.json.JSONArray().put(slice));

        assertThrows(ValidationException.class, () -> schema.validate(json));
    }

    private JSONObject loadJson(String resourceName) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        assertNotNull(is, "Test resource not found: " + resourceName);
        return new JSONObject(new JSONTokener(is));
    }
}
