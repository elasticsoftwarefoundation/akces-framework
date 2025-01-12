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

package org.elasticsoftware.akces.queries.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.queries.QueryModel;
import org.elasticsoftware.akces.queries.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.queries.QueryModelState;
import org.elasticsoftware.akces.queries.QueryModelStateType;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;

public class QueryModelRuntimeFactory<S extends QueryModelState> implements FactoryBean<QueryModelRuntime> {
    private final ListableBeanFactory applicationContext;
    private final ObjectMapper objectMapper;
    private final SchemaRegistryClient schemaRegistryClient;
    private final QueryModel<S> queryModel;

    public QueryModelRuntimeFactory(ListableBeanFactory applicationContext,
                                    ObjectMapper objectMapper,
                                    SchemaRegistryClient schemaRegistryClient,
                                    QueryModel<S> queryModel) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.schemaRegistryClient = schemaRegistryClient;
        this.queryModel = queryModel;
    }

    @Override
    public QueryModelRuntime getObject() throws Exception {
        return createRuntime(queryModel);
    }

    @Override
    public Class<?> getObjectType() {
        return QueryModelRuntime.class;
    }

    private QueryModelRuntime createRuntime(QueryModel<S> queryModel) {
        KafkaQueryModelRuntime.Builder runtimeBuilder = new KafkaQueryModelRuntime.Builder();

        QueryModelInfo queryModelInfo = queryModel.getClass().getAnnotation(QueryModelInfo.class);

        if (queryModelInfo != null) {
            runtimeBuilder.setStateType(new QueryModelStateType<S>(
                    queryModelInfo.value(),
                    queryModelInfo.version(),
                    queryModel.getStateClass(),
                    queryModelInfo.indexName()
            ));
        } else {
            throw new IllegalStateException("Class implementing Aggregate must be annotated with @AggregateInfo");
        }
        runtimeBuilder.setQueryModelClass(queryModel.getClass());
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

        return runtimeBuilder.setSchemaRegistryClient(schemaRegistryClient).build();
    }
}
