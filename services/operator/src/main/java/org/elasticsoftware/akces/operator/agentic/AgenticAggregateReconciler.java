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

package org.elasticsoftware.akces.operator.agentic;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Workflow;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import org.apache.kafka.clients.admin.NewTopic;
import org.elasticsoftware.akces.operator.utils.KafkaTopicUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Objects;

/**
 * JOSDK {@link Reconciler} for {@link AgenticAggregateResource} custom resources.
 *
 * <p>On every reconciliation loop this reconciler:
 * <ol>
 *   <li>Ensures the required Kafka topics (Commands, DomainEvents, AggregateState) exist for the
 *       AgenticAggregate. All topics always have exactly 1 partition because AgenticAggregates are
 *       singletons.</li>
 *   <li>Reads the current {@link StatefulSet} status and patches the
 *       {@link AgenticAggregateStatus#getReadyReplicas()} field accordingly.</li>
 * </ol>
 *
 * <p>The reconciler manages three dependent resources via the {@link Workflow} annotation:
 * {@link ConfigMapDependentResource}, {@link StatefulSetDependentResource}, and
 * {@link ServiceDependentResource}.
 */
@Workflow(dependents = {
        @Dependent(type = ConfigMapDependentResource.class),
        @Dependent(type = StatefulSetDependentResource.class),
        @Dependent(type = ServiceDependentResource.class)
})
@ControllerConfiguration
public class AgenticAggregateReconciler implements Reconciler<AgenticAggregateResource> {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KafkaAdmin kafkaAdmin;
    private final short replicationFactor;

    /**
     * Creates a new {@code AgenticAggregateReconciler}.
     *
     * @param kafkaAdmin        the {@link KafkaAdmin} used to create and verify Kafka topics
     * @param replicationFactor the Kafka replication factor for agentic aggregate topics
     *                          (defaults to {@code 3}; set to {@code 1} in single-broker test environments)
     */
    public AgenticAggregateReconciler(KafkaAdmin kafkaAdmin, short replicationFactor) {
        this.kafkaAdmin = kafkaAdmin;
        this.replicationFactor = replicationFactor;
    }

    /**
     * Reconciles the desired state of the given {@link AgenticAggregateResource}.
     *
     * <p>Ensures that the Kafka topics for the AgenticAggregate exist, then patches the status
     * subresource with the current ready replica count from the secondary {@link StatefulSet}.
     *
     * @param resource the AgenticAggregate custom resource to reconcile
     * @param context  the reconciliation context providing access to secondary resources
     * @return an {@link UpdateControl} that patches the status, or {@link UpdateControl#noUpdate()}
     *         when the StatefulSet is not yet available
     * @throws Exception if topic creation or status patching fails
     */
    @Override
    public UpdateControl<AgenticAggregateResource> reconcile(
            AgenticAggregateResource resource, Context<AgenticAggregateResource> context) throws Exception {

        reconcileTopics(resource.getMetadata().getName());

        return context.getSecondaryResource(StatefulSet.class).map(statefulSet -> {
            var updated = createResourceForStatusUpdate(resource, statefulSet);
            log.info("Updating status of AgenticAggregate {} in namespace {} to {} ready replicas",
                    resource.getMetadata().getName(),
                    resource.getMetadata().getNamespace(),
                    updated.getStatus() == null ? 0 : updated.getStatus().getReadyReplicas());
            return UpdateControl.patchStatus(updated);
        }).orElseGet(UpdateControl::noUpdate);
    }

    /**
     * Builds a minimal {@link AgenticAggregateResource} containing only the status subresource,
     * suitable for use with {@link UpdateControl#patchStatus(Object)}.
     *
     * @param resource     the original custom resource (provides name and namespace)
     * @param statefulSet  the secondary StatefulSet whose status is reflected
     * @return a new resource instance populated with the current ready replica count
     */
    private AgenticAggregateResource createResourceForStatusUpdate(
            AgenticAggregateResource resource, StatefulSet statefulSet) {

        var res = new AgenticAggregateResource();
        res.setMetadata(new ObjectMetaBuilder()
                .withName(resource.getMetadata().getName())
                .withNamespace(resource.getMetadata().getNamespace())
                .build());

        var statefulSetStatus = Objects.requireNonNullElse(statefulSet.getStatus(), new StatefulSetStatus());
        int readyReplicas = Objects.requireNonNullElse(statefulSetStatus.getReadyReplicas(), 0);

        var status = new AgenticAggregateStatus();
        status.setReadyReplicas(readyReplicas);
        res.setStatus(status);

        return res;
    }

    /**
     * Creates or verifies the Kafka topics for the named AgenticAggregate.
     *
     * <p>Topics are always created with exactly 1 partition because AgenticAggregates are
     * singletons. The replication factor is configurable via
     * {@code akces.operator.agentic.replication-factor} (defaults to 3). The three topics
     * created are:
     * <ul>
     *   <li>{@code <name>-Commands}</li>
     *   <li>{@code <name>-DomainEvents}</li>
     *   <li>{@code <name>-AggregateState} (compacted)</li>
     * </ul>
     *
     * @param agenticAggregateName the name of the AgenticAggregate (also used as topic prefix)
     */
    private void reconcileTopics(String agenticAggregateName) {
        log.info("Reconciling topics for AgenticAggregate: {}", agenticAggregateName);
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics(agenticAggregateName, replicationFactor);
        kafkaAdmin.createOrModifyTopics(topics.toArray(new NewTopic[0]));
    }
}
