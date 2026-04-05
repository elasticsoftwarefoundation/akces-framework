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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

/**
 * Kubernetes dependent resource that manages the {@code ConfigMap} for an
 * {@link AgenticAggregateResource}.
 *
 * <p>The ConfigMap is derived from the {@code configmap.yaml} template packaged alongside this
 * class and is named {@code <aggregateName>-config}. If the spec provides additional
 * {@code applicationProperties}, they are appended to the base {@code application.properties}
 * entry in the ConfigMap data.
 *
 * <p>Only resources labelled with {@code app.kubernetes.io/managed-by=akces-operator} are
 * watched by the informer backing this dependent resource.
 */
@KubernetesDependent(informer = @Informer(
        labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class ConfigMapDependentResource extends CRUDKubernetesDependentResource<ConfigMap, AgenticAggregateResource> {

    /**
     * Creates a new {@code ConfigMapDependentResource} for {@link AgenticAggregateResource}.
     */
    public ConfigMapDependentResource() {
        super(ConfigMap.class);
    }

    /**
     * Builds the desired {@link ConfigMap} for the given {@link AgenticAggregateResource}.
     *
     * <p>The base content is loaded from {@code configmap.yaml}. The metadata is populated with
     * the resource name and namespace, and standard Akces operator labels are applied. If the spec
     * contains additional {@code applicationProperties}, they are appended after the base
     * properties.
     *
     * @param agenticAggregate the owning AgenticAggregate custom resource
     * @param context          the reconciliation context
     * @return the desired {@link ConfigMap} state
     */
    @Override
    protected ConfigMap desired(AgenticAggregateResource agenticAggregate, Context<AgenticAggregateResource> context) {
        final ObjectMeta metadata = agenticAggregate.getMetadata();
        final String aggregateName = metadata.getName();

        var configMap = new ConfigMapBuilder(
                Serialization.unmarshal(getClass().getResourceAsStream("configmap.yaml"), ConfigMap.class))
                .editMetadata()
                .withName(aggregateName + "-config")
                .withNamespace(metadata.getNamespace())
                .addToLabels("app.kubernetes.io/part-of", aggregateName)
                .addToLabels("app.kubernetes.io/managed-by", "akces-operator")
                .endMetadata()
                .build();

        // Append any extra application properties from the spec
        var extraProps = agenticAggregate.getSpec().getApplicationProperties();
        if (extraProps != null && !extraProps.isBlank()) {
            var existingProps = configMap.getData().get("application.properties");
            configMap.getData().put("application.properties", existingProps + "\n" + extraProps);
        }

        return configMap;
    }
}
