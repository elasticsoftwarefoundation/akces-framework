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

package org.elasticsoftware.akces.client.exceptions;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.client.AkcesClientCommandException;
import org.elasticsoftware.akces.client.UnroutableCommandException;
import org.elasticsoftware.akces.commands.Command;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UnroutableCommandExceptionTests {

    @Test
    public void testExceptionCreation() {
        UnroutableCommandException exception = new UnroutableCommandException(TestCommand.class);
        
        assertNotNull(exception);
        assertInstanceOf(AkcesClientCommandException.class, exception);
        assertTrue(exception.getMessage().contains("Unable to Route Command"));
    }

    @Test
    public void testExceptionMessage() {
        UnroutableCommandException exception = new UnroutableCommandException(TestCommand.class);
        
        String message = exception.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("no AggregateService found"));
    }

    @Test
    public void testExceptionCanBeThrown() {
        assertThrows(UnroutableCommandException.class, () -> {
            throw new UnroutableCommandException(TestCommand.class);
        });
    }

    @CommandInfo(type = "TestCommand", version = 1)
    static class TestCommand implements Command {
    }
}
