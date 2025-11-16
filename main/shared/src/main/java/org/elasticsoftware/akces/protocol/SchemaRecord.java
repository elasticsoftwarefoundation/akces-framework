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

package org.elasticsoftware.akces.protocol;

import io.confluent.kafka.schemaregistry.json.JsonSchema;

/**
 * Record for storing schema information in Kafka.
 * 
 * @param schemaName the name of the schema (e.g., "CreateWalletCommand", "WalletCreatedEvent")
 * @param version the version of the schema
 * @param schema the JSON Schema definition
 * @param registeredAt timestamp when the schema was registered (Unix timestamp in milliseconds)
 */
public record SchemaRecord(
        String schemaName,
        int version,
        JsonSchema schema,
        long registeredAt
) {
}
