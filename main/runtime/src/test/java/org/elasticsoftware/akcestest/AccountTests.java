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

package org.elasticsoftware.akcestest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import jakarta.inject.Inject;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akcestest.aggregate.account.AccountState;
import org.elasticsoftware.akcestest.aggregate.account.PreviousAccountState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(classes = AccountConfiguration.class, properties = "spring.autoconfigure.exclude=org.elasticsoftware.akces.client.AkcesClientAutoConfiguration")
public class AccountTests {
    @Inject
    ApplicationContext applicationContext;
    @Inject
    ObjectMapper objectMapper;
    @Inject
    KafkaSchemaRegistry schemaRegistry;
    @Inject
    SchemaRegistryClient schemaRegistryClient;

    @Test
    public void testAggregateStateSerializationWithChangingSchema() throws JsonProcessingException {
        String userId = "a2b04808-19d7-4ec8-94db-1ce28ca517f1";
        String serializedState= objectMapper.writeValueAsString(new PreviousAccountState(userId, "US", "John", "Doe", "john.doe@example.com"));
        AccountState accountState = objectMapper.readValue(serializedState, AccountState.class);
        Assertions.assertEquals(userId, accountState.userId());
        Assertions.assertNotNull(accountState.twoFactorEnabled());
        Assertions.assertFalse(accountState.twoFactorEnabled());
    }
}
