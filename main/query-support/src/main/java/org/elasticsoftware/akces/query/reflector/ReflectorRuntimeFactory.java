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

package org.elasticsoftware.akces.query.reflector;

import tools.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.ReflectorInfo;
import org.elasticsoftware.akces.query.Reflector;
import org.elasticsoftware.akces.query.ReflectorEventHandlerFunction;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class ReflectorRuntimeFactory implements FactoryBean<ReflectorRuntime>, ApplicationContextAware {
    private ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final Reflector<?> reflector;

    public ReflectorRuntimeFactory(ObjectMapper objectMapper, Reflector<?> reflector) {
        this.objectMapper = objectMapper;
        this.reflector = reflector;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public ReflectorRuntime getObject() throws Exception {
        return createRuntime(reflector);
    }

    @Override
    public Class<?> getObjectType() {
        return ReflectorRuntime.class;
    }

    private ReflectorRuntime createRuntime(Reflector<?> reflector) {
        KafkaReflectorRuntime.Builder runtimeBuilder = new KafkaReflectorRuntime.Builder();

        ReflectorInfo reflectorInfo = reflector.getClass().getAnnotation(ReflectorInfo.class);

        if (reflectorInfo == null) {
            throw new IllegalStateException("Class implementing Reflector must be annotated with @ReflectorInfo");
        }
        runtimeBuilder
                .setReflectorInfo(reflectorInfo)
                .setObjectMapper(objectMapper);
        // ReflectorEventHandlers
        applicationContext.getBeansOfType(ReflectorEventHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getReflector().equals(reflector))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    runtimeBuilder.addEventHandlerFunction(type, adapter);
                    runtimeBuilder.addDomainEvent(type);
                });

        return runtimeBuilder.build();
    }
}
