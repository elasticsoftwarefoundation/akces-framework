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

package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CommandTypeTests {

    @Test
    public void testCommandTypeCreation() {
        CommandType<TestCommand> commandType = new CommandType<>(
            "TestCommand",
            1,
            TestCommand.class,
            true,
            false,
            false
        );
        
        assertEquals("TestCommand", commandType.typeName());
        assertEquals(1, commandType.version());
        assertEquals(TestCommand.class, commandType.typeClass());
        assertTrue(commandType.create());
        assertFalse(commandType.external());
        assertFalse(commandType.piiData());
    }

    @Test
    public void testGetSchemaPrefix() {
        CommandType<TestCommand> commandType = new CommandType<>(
            "TestCommand",
            1,
            TestCommand.class,
            false,
            false,
            false
        );
        
        assertEquals("commands.", commandType.getSchemaPrefix());
    }

    @Test
    public void testRelaxExternalValidation() {
        CommandType<TestCommand> commandType = new CommandType<>(
            "TestCommand",
            1,
            TestCommand.class,
            false,
            true,
            false
        );
        
        assertFalse(commandType.relaxExternalValidation());
    }

    @Test
    public void testCommandTypeWithPIIData() {
        CommandType<TestCommand> commandType = new CommandType<>(
            "TestCommand",
            2,
            TestCommand.class,
            false,
            false,
            true
        );
        
        assertTrue(commandType.piiData());
    }

    @Test
    public void testCommandTypeEquality() {
        CommandType<TestCommand> commandType1 = new CommandType<>(
            "TestCommand",
            1,
            TestCommand.class,
            true,
            false,
            false
        );
        
        CommandType<TestCommand> commandType2 = new CommandType<>(
            "TestCommand",
            1,
            TestCommand.class,
            true,
            false,
            false
        );
        
        assertEquals(commandType1, commandType2);
        assertEquals(commandType1.hashCode(), commandType2.hashCode());
    }

    static class TestCommand implements Command {
    }
}
