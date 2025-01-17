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

package org.elasticsoftware.akces.schemas;

public class InvalidSchemaVersionException extends SchemaException {
    private final int schemaVersion;

    public InvalidSchemaVersionException(String schemaIdentifier,
                                         int highestRegisteredSchemaVersion,
                                         int schemaVersion,
                                         Class<?> implementationClass) {
        super("Invalid Schema Version, highest registered schema version is "+highestRegisteredSchemaVersion,
                schemaIdentifier,
                implementationClass);
        this.schemaVersion = schemaVersion;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

}
