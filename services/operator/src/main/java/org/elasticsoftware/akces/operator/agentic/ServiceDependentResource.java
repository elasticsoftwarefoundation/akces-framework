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

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

/**
 * Kubernetes dependent resource that manages the ClusterIP {@code Service} for an
 * {@link AgenticAggregateResource}.
 *
 * <p>The Service is derived from the {@code service.yaml} template packaged alongside this class
 * and is named {@code <aggregateName>-service}. It is a {@code ClusterIP} service that routes
 * traffic on port 80 to the pod's HTTP port 8080.
 *
 * <p>Only resources labelled with {@code app.kubernetes.io/managed-by=akces-operator} are
 * watched by the informer backing this dependent resource.
 */
@KubernetesDependent(informer = @Informer(
        labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class ServiceDependentResource extends CRUDKubernetesDependentResource<Service, AgenticAggregateResource> {

    /**
     * Creates a new {@code ServiceDependentResource} for {@link AgenticAggregateResource}.
     */
    public ServiceDependentResource() {
        super(Service.class);
    }

    /**
     * Builds the desired {@link Service} for the given {@link AgenticAggregateResource}.
     *
     * <p>The base content is loaded from {@code service.yaml}. The metadata is populated with the
     * resource name and namespace, and standard Akces operator labels are applied. The service
     * selector is configured to match pods with the {@code app=<aggregateName>} label.
     *
     * @param agenticAggregate the owning AgenticAggregate custom resource
     * @param context          the reconciliation context
     * @return the desired {@link Service} state
     */
    @Override
    protected Service desired(AgenticAggregateResource agenticAggregate, Context<AgenticAggregateResource> context) {
        final ObjectMeta metadata = agenticAggregate.getMetadata();
        final String aggregateName = metadata.getName();

        return new ServiceBuilder(
                Serialization.unmarshal(getClass().getResourceAsStream("service.yaml"), Service.class))
                .editMetadata()
                .withName(aggregateName + "-service")
                .withNamespace(metadata.getNamespace())
                .addToLabels("app.kubernetes.io/part-of", aggregateName)
                .addToLabels("app.kubernetes.io/managed-by", "akces-operator")
                .endMetadata()
                .editSpec()
                .addToSelector("app", aggregateName)
                .endSpec()
                .build();
    }
}
