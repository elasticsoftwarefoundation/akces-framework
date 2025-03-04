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

package org.elasticsoftware.akces.beans;

import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.springframework.aot.generate.ValueCodeGenerator;
import org.springframework.javapoet.CodeBlock;

public class DomainEventTypeValueCodeGeneratorDelegate implements ValueCodeGenerator.Delegate {
    public DomainEventTypeValueCodeGeneratorDelegate() {
    }

    @Override
    public CodeBlock generateCode(ValueCodeGenerator valueCodeGenerator, Object value) {
        if (value instanceof DomainEventType<?>(
                String typeName, int version, Class<?> typeClass, boolean create, boolean external, boolean error, Boolean piiData
        )) {
            return CodeBlock.builder()
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
        } else {
            return null;
        }
    }
}
