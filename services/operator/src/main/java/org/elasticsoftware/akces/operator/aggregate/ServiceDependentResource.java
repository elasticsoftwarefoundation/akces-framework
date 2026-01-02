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

package org.elasticsoftware.akces.operator.aggregate;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.javaoperatorsdk.operator.ReconcilerUtils;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

@KubernetesDependent(informer = @Informer(
        labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class ServiceDependentResource extends CRUDKubernetesDependentResource<Service, Aggregate> {

    public ServiceDependentResource() {
        super(Service.class);
    }

    @Override
    protected Service desired(Aggregate aggregate, Context<Aggregate> context) {
        final ObjectMeta aggregateMetadata = aggregate.getMetadata();
        final String aggregateName = aggregateMetadata.getName();
        return new ServiceBuilder(ReconcilerUtils.loadYaml(Service.class, getClass(), "service.yaml"))
                .editMetadata()
                .withName(aggregateName + "-service")
                .withNamespace(aggregateMetadata.getNamespace())
                .addToLabels("app.kubernetes.io/part-of", aggregateName)
                .addToLabels("app.kubernetes.io/managed-by", "akces-operator")
                .endMetadata()
                .editSpec()
                .addToSelector("app", aggregateMetadata.getName())
                .endSpec()
                .build();
    }

}
