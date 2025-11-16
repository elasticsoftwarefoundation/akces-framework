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

package org.elasticsoftware.akces.query.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.DatabaseModelInfo;
import org.elasticsoftware.akces.query.DatabaseModel;
import org.elasticsoftware.akces.query.DatabaseModelEventHandlerFunction;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class DatabaseModelRuntimeFactory implements FactoryBean<DatabaseModelRuntime>, ApplicationContextAware {
    private ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final DatabaseModel databaseModel;

    public DatabaseModelRuntimeFactory(ObjectMapper objectMapper,
                                       DatabaseModel databaseModel) {
        this.objectMapper = objectMapper;
        this.databaseModel = databaseModel;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public DatabaseModelRuntime getObject() throws Exception {
        return createRuntime(databaseModel);
    }

    @Override
    public Class<?> getObjectType() {
        return DatabaseModelRuntime.class;
    }

    private DatabaseModelRuntime createRuntime(DatabaseModel databaseModel) {
        KafkaDatabaseModelRuntime.Builder runtimeBuilder = new KafkaDatabaseModelRuntime.Builder();

        DatabaseModelInfo databaseModelInfo = databaseModel.getClass().getAnnotation(DatabaseModelInfo.class);

        if (databaseModelInfo == null) {
            throw new IllegalStateException("Class implementing DatabaseModel must be annotated with @DatabaseModelInfo");
        }
        runtimeBuilder
                .setDatabaseModelInfo(databaseModelInfo)
                .setDatabaseModel(databaseModel)
                .setObjectMapper(objectMapper);
        // DatabaseModelEventHandlers
        applicationContext.getBeansOfType(DatabaseModelEventHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getDatabaseModel().equals(databaseModel))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    runtimeBuilder.addDatabaseModelEventHandler(type, adapter);
                    runtimeBuilder.addDomainEvent(type);
                });

        return runtimeBuilder.build();
    }
}
