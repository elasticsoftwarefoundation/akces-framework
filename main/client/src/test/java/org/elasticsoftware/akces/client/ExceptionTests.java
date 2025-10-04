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

import org.elasticsoftware.akces.client.commands.CreateAccountCommand;
import org.elasticsoftware.akces.client.commands.InvalidCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ExceptionTests {

    @Test
    public void testCommandRefusedException() {
        CommandRefusedException exception = new CommandRefusedException(
                CreateAccountCommand.class,
                AkcesClientControllerState.INITIALIZING
        );

        assertNotNull(exception);
        assertEquals(CreateAccountCommand.class, exception.getCommandClass());
        assertEquals("CreateAccount", exception.getCommandType());
        assertEquals(1, exception.getCommandVersion());
        assertEquals(AkcesClientControllerState.INITIALIZING, exception.getState());
        assertTrue(exception.getMessage().contains("Command Refused because AkcesClient is not in RUNNING state"));
    }

    @Test
    public void testCommandRefusedExceptionWithShuttingDownState() {
        CommandRefusedException exception = new CommandRefusedException(
                CreateAccountCommand.class,
                AkcesClientControllerState.SHUTTING_DOWN
        );

        assertEquals(AkcesClientControllerState.SHUTTING_DOWN, exception.getState());
    }

    @Test
    public void testCommandSerializationException() {
        RuntimeException cause = new RuntimeException("Serialization failed");
        CommandSerializationException exception = new CommandSerializationException(
                CreateAccountCommand.class,
                cause
        );

        assertNotNull(exception);
        assertEquals(CreateAccountCommand.class, exception.getCommandClass());
        assertEquals("CreateAccount", exception.getCommandType());
        assertEquals(1, exception.getCommandVersion());
        assertSame(cause, exception.getCause());
        assertTrue(exception.getMessage().contains("Serializing"));
    }

    @Test
    public void testCommandValidationException() {
        RuntimeException cause = new RuntimeException("Validation failed");
        CommandValidationException exception = new CommandValidationException(
                CreateAccountCommand.class,
                cause
        );

        assertNotNull(exception);
        assertEquals(CreateAccountCommand.class, exception.getCommandClass());
        assertEquals("CreateAccount", exception.getCommandType());
        assertEquals(1, exception.getCommandVersion());
        assertSame(cause, exception.getCause());
        assertTrue(exception.getMessage().contains("Validating"));
    }

    @Test
    public void testCommandSendingFailedException() {
        RuntimeException cause = new RuntimeException("Sending failed");
        CommandSendingFailedException exception = new CommandSendingFailedException(
                CreateAccountCommand.class,
                cause
        );

        assertNotNull(exception);
        assertEquals(CreateAccountCommand.class, exception.getCommandClass());
        assertEquals("CreateAccount", exception.getCommandType());
        assertEquals(1, exception.getCommandVersion());
        assertSame(cause, exception.getCause());
        assertTrue(exception.getMessage().contains("Sending"));
    }

    @Test
    public void testUnroutableCommandException() {
        UnroutableCommandException exception = new UnroutableCommandException(
                CreateAccountCommand.class
        );

        assertNotNull(exception);
        assertEquals(CreateAccountCommand.class, exception.getCommandClass());
        assertEquals("CreateAccount", exception.getCommandType());
        assertEquals(1, exception.getCommandVersion());
        assertTrue(exception.getMessage().contains("Unable to Route Command"));
        assertTrue(exception.getMessage().contains("no AggregateService found"));
    }

    @Test
    public void testMissingDomainEventException() {
        MissingDomainEventException exception = new MissingDomainEventException(
                CreateAccountCommand.class,
                "AccountCreated",
                2
        );

        assertNotNull(exception);
        assertEquals(CreateAccountCommand.class, exception.getCommandClass());
        assertEquals("CreateAccount", exception.getCommandType());
        assertEquals(1, exception.getCommandVersion());
        assertEquals("AccountCreated", exception.getSchemaName());
        assertEquals(2, exception.getVersion());
        assertTrue(exception.getMessage().contains("missing local version of DomainEvent"));
        assertTrue(exception.getMessage().contains("AccountCreated v2"));
    }

    @Test
    public void testUnknownSchemaException() {
        UnknownSchemaException exception = new UnknownSchemaException(
                CreateAccountCommand.class,
                "commands.UnknownCommand-v1"
        );

        assertNotNull(exception);
        assertEquals(CreateAccountCommand.class, exception.getCommandClass());
        assertEquals("CreateAccount", exception.getCommandType());
        assertEquals(1, exception.getCommandVersion());
        assertEquals("commands.UnknownCommand-v1", exception.getSchemaIdentifier());
        assertTrue(exception.getMessage().contains("Unknown Schema"));
        assertTrue(exception.getMessage().contains("commands.UnknownCommand-v1"));
    }

    @Test
    public void testAkcesClientCommandExceptionWithNullCommandInfo() {
        // Test with InvalidCommand which has no CommandInfo annotation
        CommandSerializationException exception = new CommandSerializationException(
                InvalidCommand.class,
                new RuntimeException("test")
        );

        assertEquals(InvalidCommand.class, exception.getCommandClass());
        assertNull(exception.getCommandType());
        assertNull(exception.getCommandVersion());
    }

    @Test
    public void testAkcesClientCommandExceptionMessageOnly() {
        UnroutableCommandException exception = new UnroutableCommandException(
                CreateAccountCommand.class
        );

        assertNotNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    public void testAkcesClientCommandExceptionWithCause() {
        RuntimeException cause = new RuntimeException("Root cause");
        CommandSerializationException exception = new CommandSerializationException(
                CreateAccountCommand.class,
                cause
        );

        assertNotNull(exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
}
