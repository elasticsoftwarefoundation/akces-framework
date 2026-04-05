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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes dependent resource that manages the {@code StatefulSet} for an
 * {@link AgenticAggregateResource}.
 *
 * <p>The StatefulSet is derived from the {@code statefulset.yaml} template packaged alongside
 * this class. AgenticAggregates are always deployed as a singleton (1 replica), so the replica
 * count is fixed by the template and is never overridden from the spec.
 *
 * <p>Any sidecar containers declared in {@link AgenticAggregateSpec#getSidecars()} are appended
 * to the pod's container list after the primary {@code akces-agentic-aggregate-service} container.
 * Additional environment variables from {@link AgenticAggregateSpec#getEnv()} are merged into the
 * primary container.
 *
 * <p>Only resources labelled with {@code app.kubernetes.io/managed-by=akces-operator} are
 * watched by the informer backing this dependent resource.
 */
@KubernetesDependent(informer = @Informer(
        labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class StatefulSetDependentResource extends CRUDKubernetesDependentResource<StatefulSet, AgenticAggregateResource> {

    /**
     * Creates a new {@code StatefulSetDependentResource} for {@link AgenticAggregateResource}.
     */
    public StatefulSetDependentResource() {
        super(StatefulSet.class);
    }

    /**
     * Builds the desired {@link StatefulSet} for the given {@link AgenticAggregateResource}.
     *
     * <p>The base configuration is loaded from {@code statefulset.yaml}. The method then:
     * <ol>
     *   <li>Sets the metadata (name, namespace, labels).</li>
     *   <li>Wires up the service name, pod selector, and template labels.</li>
     *   <li>Configures the primary container image, name, args, environment variables, and
     *       resource requirements.</li>
     *   <li>Points the {@code config-volume} ConfigMap mount at the correct ConfigMap name.</li>
     *   <li>Applies additional env vars from the spec, if any.</li>
     *   <li>Appends sidecar containers from the spec, if any.</li>
     * </ol>
     *
     * <p>The replica count is intentionally left at the template default of {@code 1} because
     * AgenticAggregates are singletons.
     *
     * @param agenticAggregate the owning AgenticAggregate custom resource
     * @param context          the reconciliation context
     * @return the desired {@link StatefulSet} state
     */
    @Override
    protected StatefulSet desired(AgenticAggregateResource agenticAggregate, Context<AgenticAggregateResource> context) {
        final ObjectMeta metadata = agenticAggregate.getMetadata();
        final String aggregateName = metadata.getName();
        final var spec = agenticAggregate.getSpec();

        // Load base StatefulSet from template and apply configuration
        var statefulSet = Serialization.unmarshal(
                getClass().getResourceAsStream("statefulset.yaml"), StatefulSet.class);

        var builder = new StatefulSetBuilder(statefulSet)
                .editMetadata()
                .withName(aggregateName)
                .withNamespace(metadata.getNamespace())
                .addToLabels("app", aggregateName)
                .addToLabels("app.kubernetes.io/part-of", aggregateName)
                .addToLabels("app.kubernetes.io/managed-by", "akces-operator")
                .endMetadata()
                .editSpec()
                .withServiceName(aggregateName + "-service")
                .editSelector().addToMatchLabels("app", aggregateName).endSelector()
                // Replica count is intentionally NOT set here — AgenticAggregates are always 1 replica
                .editTemplate()
                .editMetadata().addToLabels("app", aggregateName).endMetadata()
                .editSpec()
                .addToImagePullSecrets(new LocalObjectReference("github-packages-cfg")) // TODO: needs to be configurable
                .editFirstContainer()
                .withImage(spec.getImage())
                .withName("akces-agentic-aggregate-service")
                .withArgs(spec.getArgs())
                .editFirstEnv()
                .withValue(spec.getApplicationName())
                .endEnv()
                .editLastEnv()
                .withValue(String.valueOf(spec.isEnableSchemaOverwrites()))
                .endEnv()
                .withResources(spec.getResources())
                .endContainer()
                .editFirstVolume()
                .editConfigMap()
                .withName(aggregateName + "-config")
                .endConfigMap()
                .endVolume()
                .endSpec()
                .endTemplate()
                .editFirstVolumeClaimTemplate()
                .editSpec()
                .withStorageClassName("akces-data-hyperdisk-balanced") // TODO: get from config
                .editResources()
                .withRequests(Map.of("storage", new Quantity("4Gi"))) // TODO: get from spec
                .endResources()
                .endSpec()
                .endVolumeClaimTemplate()
                .endSpec();

        statefulSet = builder.build();

        // Merge additional env vars from the spec into the primary container
        if (spec.getEnv() != null && !spec.getEnv().isEmpty()) {
            statefulSet = new StatefulSetBuilder(statefulSet)
                    .editSpec()
                    .editTemplate()
                    .editSpec()
                    .editFirstContainer()
                    .addAllToEnv(spec.getEnv())
                    .endContainer()
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build();
        }

        // Append sidecar containers declared in the spec
        if (spec.getSidecars() != null && !spec.getSidecars().isEmpty()) {
            List<Container> sidecarContainers = spec.getSidecars().stream()
                    .map(sidecar -> new ContainerBuilder()
                            .withName(sidecar.getName())
                            .withImage(sidecar.getImage())
                            .withEnv(sidecar.getEnv())
                            .withPorts(sidecar.getPorts())
                            .withResources(sidecar.getResources())
                            .withReadinessProbe(sidecar.getReadinessProbe())
                            .withLivenessProbe(sidecar.getLivenessProbe())
                            .build())
                    .toList();

            statefulSet = new StatefulSetBuilder(statefulSet)
                    .editSpec()
                    .editTemplate()
                    .editSpec()
                    .addToContainers(sidecarContainers.toArray(new Container[0]))
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build();
        }

        return statefulSet;
    }
}
