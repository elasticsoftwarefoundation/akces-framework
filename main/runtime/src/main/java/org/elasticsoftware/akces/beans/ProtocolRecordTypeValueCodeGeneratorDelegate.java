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

package org.elasticsoftware.akces.beans;

import org.elasticsoftware.akces.aggregate.AggregateStateType;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.springframework.aot.generate.ValueCodeGenerator;
import org.springframework.javapoet.CodeBlock;

public class ProtocolRecordTypeValueCodeGeneratorDelegate implements ValueCodeGenerator.Delegate {
    public ProtocolRecordTypeValueCodeGeneratorDelegate() {
    }

    @Override
    public CodeBlock generateCode(ValueCodeGenerator valueCodeGenerator, Object value) {
        return switch (value) {
            case DomainEventType<?>(
                    String typeName, int version, Class<?> typeClass, boolean create, boolean external, boolean error,
                    boolean piiData
            ) -> CodeBlock.builder()
                    .add("new $T($S, $L, $T.class, $L, $L, $L, $L)",
                            DomainEventType.class,
                            typeName,
                            version,
                            typeClass,
                            create,
                            external,
                            error,
                            piiData)
                    .build();
            case CommandType<?>(
                    String typeName, int version, Class<?> typeClass, boolean create, boolean external, boolean piiData
            ) -> CodeBlock.builder()
                    .add("new $T($S, $L, $T.class, $L, $L, $L)",
                            CommandType.class,
                            typeName,
                            version,
                            typeClass,
                            create,
                            external,
                            piiData)
                    .build();
            case AggregateStateType<?>(
                    String typeName, int version, Class<?> typeClass, boolean generateGDPRKeyOnCreate,
                    boolean indexed, String indexName, boolean piiData
            ) -> CodeBlock.builder()
                    .add("new $T($S, $L, $T.class, $L, $L, $S, $L)",
                            AggregateStateType.class,
                            typeName,
                            version,
                            typeClass,
                            generateGDPRKeyOnCreate,
                            indexed,
                            indexName,
                            piiData)
                    .build();
            default -> null;
        };
    }
}
