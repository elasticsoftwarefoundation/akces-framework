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

package org.elasticsoftware.akces.query.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.akces.query.QueryModelStateType;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class QueryModelRuntimeFactory<S extends QueryModelState> implements FactoryBean<QueryModelRuntime<S>>, ApplicationContextAware {
    private ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry;
    private final QueryModel<S> queryModel;

    public QueryModelRuntimeFactory(ObjectMapper objectMapper,
                                    SchemaRegistry schemaRegistry,
                                    QueryModel<S> queryModel) {
        this.objectMapper = objectMapper;
        this.schemaRegistry = schemaRegistry;
        this.queryModel = queryModel;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public QueryModelRuntime<S> getObject() throws Exception {
        return createRuntime(queryModel);
    }

    @Override
    public Class<?> getObjectType() {
        return QueryModelRuntime.class;
    }

    private QueryModelRuntime<S> createRuntime(QueryModel<S> queryModel) {
        KafkaQueryModelRuntime.Builder<S> runtimeBuilder = new KafkaQueryModelRuntime.Builder<>();

        QueryModelInfo queryModelInfo = queryModel.getClass().getAnnotation(QueryModelInfo.class);

        if (queryModelInfo != null) {
            runtimeBuilder.setStateType(new QueryModelStateType<>(
                    queryModelInfo.value(),
                    queryModelInfo.version(),
                    queryModel.getStateClass(),
                    queryModelInfo.indexName()
            ));
        } else {
            throw new IllegalStateException("Class implementing Aggregate must be annotated with @QueryModelInfo");
        }
        runtimeBuilder.setQueryModelClass((Class<? extends QueryModel<S>>) queryModel.getClass());
        runtimeBuilder.setObjectMapper(objectMapper);
        // QueryModelEventHandlers
        applicationContext.getBeansOfType(QueryModelEventHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getQueryModel().equals(queryModel))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setCreateHandler(adapter);
                        runtimeBuilder.addDomainEvent(type);
                    } else {
                        runtimeBuilder.addQueryModelEventHandler(type, adapter);
                        runtimeBuilder.addDomainEvent(type);
                    }
                });

        return runtimeBuilder.setSchemaRegistry(schemaRegistry).build();
    }
}
