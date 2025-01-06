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

package org.elasticsoftware.akces.operator.aggregate;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.admin.NewTopic;
import org.elasticsoftware.akces.operator.utils.KafkaTopicUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Objects;

@Workflow(dependents = {
        @Dependent(type = StatefulSetDependentResource.class),
        @Dependent(type = ServiceDependentResource.class)
})
@ControllerConfiguration
public class AggregateReconciler implements Reconciler<Aggregate> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KafkaAdmin kafkaAdmin;
    private Integer partitions;

    public AggregateReconciler(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @PostConstruct
    public void init() {
        partitions = kafkaAdmin.describeTopics("Akces-Control").get("Akces-Control").partitions().size();
        log.info("Found Akces-Control Topic with {} partitions", partitions);
    }

    @Override
    public UpdateControl<Aggregate> reconcile(Aggregate resource, Context<Aggregate> context) throws Exception {
        reconcileTopics(resource.getSpec().getAggregateNames());
        return context.getSecondaryResource(StatefulSet.class).map(statefulSet -> {
            Aggregate updatedAggregate = createAggregateForStatusUpdate(resource, statefulSet);
            log.info(
                    "Updating status of Aggregate {} in namespace {} to {} ready replicas",
                    resource.getMetadata().getName(),
                    resource.getMetadata().getNamespace(),
                    resource.getStatus() == null ? 0 : resource.getStatus().getReadyReplicas());
            return UpdateControl.patchStatus(updatedAggregate);
        }).orElseGet(UpdateControl::noUpdate);
    }

    private Aggregate createAggregateForStatusUpdate(Aggregate tomcat, StatefulSet statefulSet) {
        Aggregate res = new Aggregate();
        res.setMetadata(new ObjectMetaBuilder()
                .withName(tomcat.getMetadata().getName())
                .withNamespace(tomcat.getMetadata().getNamespace())
                .build());
        StatefulSetStatus statefulSetStatus =
                Objects.requireNonNullElse(statefulSet.getStatus(), new StatefulSetStatus());
        int readyReplicas = Objects.requireNonNullElse(statefulSetStatus.getReadyReplicas(), 0);
        AggregateStatus status = new AggregateStatus();
        status.setReadyReplicas(readyReplicas);
        res.setStatus(status);
        return res;
    }

    private void reconcileTopics(List<String> aggregateNames) {
        log.info("Reconciling topics for Aggregates: {}", aggregateNames);
        List<NewTopic> topics = aggregateNames.stream()
                .map(name -> KafkaTopicUtils.createTopics(name, 3))
                .flatMap(List::stream).toList();
        kafkaAdmin.createOrModifyTopics(topics.toArray(new NewTopic[0]));
    }
}
