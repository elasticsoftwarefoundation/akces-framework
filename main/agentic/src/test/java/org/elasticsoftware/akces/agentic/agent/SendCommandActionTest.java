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

package org.elasticsoftware.akces.agentic.agent;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SendCommandAction}.
 */
class SendCommandActionTest {

    private final SendCommandAction action = new SendCommandAction();

    @Test
    void sendCommandDispatchesViaCommandBus() {
        Command command = mock(Command.class);
        CommandBus commandBus = mock(CommandBus.class);

        action.sendCommand(command, commandBus);

        verify(commandBus, times(1)).send(command);
    }

    @Test
    void sendCommandUsesExactCommandInstance() {
        Command command = mock(Command.class);
        CommandBus commandBus = mock(CommandBus.class);

        action.sendCommand(command, commandBus);

        verify(commandBus).send(same(command));
    }
}
