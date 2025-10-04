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

package org.elasticsoftware.akces.client;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.elasticsoftware.akces.client.commands.CreateAccountCommand;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.events.DomainEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

public class AkcesClientInterfaceTests {

    @Test
    public void testDefaultTenantIdConstant() {
        assertEquals("DefaultTenant", AkcesClient.DEFAULT_TENANT_ID);
    }

    @Test
    public void testSendWithTenantIdAndCommand() {
        TestAkcesClient client = new TestAkcesClient();
        CreateAccountCommand command = new CreateAccountCommand("user1", "NL", "John", "Doe", "john@example.com");

        CompletionStage<List<DomainEvent>> result = client.send("TEST_TENANT", command);

        assertNotNull(result);
        assertEquals("TEST_TENANT", client.lastTenantId);
        assertNull(client.lastCorrelationId);
        assertSame(command, client.lastCommand);
    }

    @Test
    public void testSendWithCommandOnly() {
        TestAkcesClient client = new TestAkcesClient();
        CreateAccountCommand command = new CreateAccountCommand("user2", "NL", "Jane", "Doe", "jane@example.com");

        CompletionStage<List<DomainEvent>> result = client.send(command);

        assertNotNull(result);
        assertEquals(AkcesClient.DEFAULT_TENANT_ID, client.lastTenantId);
        assertNull(client.lastCorrelationId);
        assertSame(command, client.lastCommand);
    }

    @Test
    public void testSendWithCommandAndCorrelationId() {
        TestAkcesClient client = new TestAkcesClient();
        CreateAccountCommand command = new CreateAccountCommand("user3", "NL", "Bob", "Smith", "bob@example.com");
        String correlationId = "corr-123";

        CompletionStage<List<DomainEvent>> result = client.send(command, correlationId);

        assertNotNull(result);
        assertEquals(AkcesClient.DEFAULT_TENANT_ID, client.lastTenantId);
        assertEquals(correlationId, client.lastCorrelationId);
        assertSame(command, client.lastCommand);
    }

    @Test
    public void testSendAndForgetWithCommandOnly() {
        TestAkcesClient client = new TestAkcesClient();
        CreateAccountCommand command = new CreateAccountCommand("user4", "NL", "Alice", "Johnson", "alice@example.com");

        client.sendAndForget(command);

        assertEquals(AkcesClient.DEFAULT_TENANT_ID, client.lastTenantIdForget);
        assertNull(client.lastCorrelationIdForget);
        assertSame(command, client.lastCommandForget);
    }

    @Test
    public void testSendAndForgetWithCommandAndCorrelationId() {
        TestAkcesClient client = new TestAkcesClient();
        CreateAccountCommand command = new CreateAccountCommand("user5", "NL", "Charlie", "Brown", "charlie@example.com");
        String correlationId = "corr-456";

        client.sendAndForget(command, correlationId);

        assertEquals(AkcesClient.DEFAULT_TENANT_ID, client.lastTenantIdForget);
        assertEquals(correlationId, client.lastCorrelationIdForget);
        assertSame(command, client.lastCommandForget);
    }

    @Test
    public void testSendAndForgetWithTenantIdAndCommand() {
        TestAkcesClient client = new TestAkcesClient();
        CreateAccountCommand command = new CreateAccountCommand("user6", "NL", "David", "Wilson", "david@example.com");

        client.sendAndForget("CUSTOM_TENANT", command);

        assertEquals("CUSTOM_TENANT", client.lastTenantIdForget);
        assertNull(client.lastCorrelationIdForget);
        assertSame(command, client.lastCommandForget);
    }

    @Test
    public void testDefaultMethodsCallCoreMethod() {
        TestAkcesClient client = new TestAkcesClient();
        CreateAccountCommand command = new CreateAccountCommand("user7", "NL", "Eve", "Taylor", "eve@example.com");

        // Test that default methods delegate to the core method
        client.send("TENANT1", command);
        assertEquals(1, client.sendCallCount);

        client.send(command);
        assertEquals(2, client.sendCallCount);

        client.send(command, "corr-789");
        assertEquals(3, client.sendCallCount);

        client.sendAndForget(command);
        assertEquals(1, client.sendAndForgetCallCount);

        client.sendAndForget(command, "corr-012");
        assertEquals(2, client.sendAndForgetCallCount);

        client.sendAndForget("TENANT2", command);
        assertEquals(3, client.sendAndForgetCallCount);
    }

    /**
     * Test implementation of AkcesClient for testing default methods
     */
    private static class TestAkcesClient implements AkcesClient {
        String lastTenantId;
        String lastCorrelationId;
        Command lastCommand;
        String lastTenantIdForget;
        String lastCorrelationIdForget;
        Command lastCommandForget;
        int sendCallCount = 0;
        int sendAndForgetCallCount = 0;

        @Override
        public CompletionStage<List<DomainEvent>> send(@Nonnull String tenantId, 
                                                        @Nullable String correlationId, 
                                                        @Nonnull Command command) {
            this.lastTenantId = tenantId;
            this.lastCorrelationId = correlationId;
            this.lastCommand = command;
            this.sendCallCount++;
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public void sendAndForget(@Nonnull String tenantId, 
                                  @Nullable String correlationId, 
                                  @Nonnull Command command) {
            this.lastTenantIdForget = tenantId;
            this.lastCorrelationIdForget = correlationId;
            this.lastCommandForget = command;
            this.sendAndForgetCallCount++;
        }
    }
}
