/*
 * Copyright 2022 - 2023 The Original Authors
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
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

public class CommandRefusedException extends AkcesClientException {
    private final AkcesClientControllerState state;

    public CommandRefusedException(@Nonnull Class<? extends Command> commandClass, AkcesClientControllerState state) {
        super(commandClass, commandClass.getAnnotation(CommandInfo.class), "Command Refused because AkcesClient is not in RUNNING state");
        this.state = state;
    }

    public AkcesClientControllerState getState() {
        return state;
    }
}
