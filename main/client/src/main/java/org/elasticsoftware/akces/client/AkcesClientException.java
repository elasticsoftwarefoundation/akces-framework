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
import jakarta.annotation.Nullable;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

public abstract class AkcesClientException extends RuntimeException {
    private final Class<? extends Command> commandClass;
    private final String commandType;
    private final Integer commandVersion;

    public AkcesClientException(@Nonnull Class<? extends Command> commandClass,
                                @Nullable CommandInfo commandInfo,
                                @Nonnull String causeMessage,
                                @Nonnull Throwable cause) {
        super("Problem "+causeMessage+" Command", cause);
        this.commandClass = commandClass;
        this.commandType = commandInfo != null ? commandInfo.type() : null;
        this.commandVersion = commandInfo != null ? commandInfo.version() : null;
    }

    public AkcesClientException(@Nonnull Class<? extends Command> commandClass,
                                @Nullable CommandInfo commandInfo,
                                @Nonnull String message) {
        super(message);
        this.commandClass = commandClass;
        this.commandType = commandInfo != null ? commandInfo.type() : null;
        this.commandVersion = commandInfo != null ? commandInfo.version() : null;
    }

    public Class<? extends Command> getCommandClass() {
        return commandClass;
    }

    public String getCommandType() {
        return commandType;
    }

    public Integer getCommandVersion() {
        return commandVersion;
    }
}
