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

package org.elasticsoftware.akces.operator.command;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.javaoperatorsdk.operator.ReconcilerUtils;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

@KubernetesDependent(informer = @Informer(
        labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class StatefulSetDependentResource extends CRUDKubernetesDependentResource<StatefulSet, CommandService> {
    public StatefulSetDependentResource() {
        super(StatefulSet.class);
    }

    @Override
    protected StatefulSet desired(CommandService aggregate, Context<CommandService> context) {
        StatefulSet statefulSet = ReconcilerUtils.loadYaml(StatefulSet.class, getClass(), "statefulset.yaml");
        final ObjectMeta metadata = aggregate.getMetadata();
        final String commandServiceName = metadata.getName();

        statefulSet = new StatefulSetBuilder(statefulSet)
                .editMetadata()
                .withName(commandServiceName)
                .withNamespace(metadata.getNamespace())
                .addToLabels("app", commandServiceName)
                .addToLabels("app.kubernetes.io/part-of", commandServiceName)
                .addToLabels("app.kubernetes.io/managed-by", "akces-operator")
                .endMetadata()
                .editSpec()
                .withServiceName(commandServiceName + "-service")
                .editSelector().addToMatchLabels("app", commandServiceName).endSelector()
                .withReplicas(aggregate.getSpec().getReplicas())
                .editTemplate()
                .editMetadata().addToLabels("app", commandServiceName).endMetadata()
                .editSpec()
                .addToImagePullSecrets(new LocalObjectReference("github-packages-cfg"))  // TODO: needs to be configurable
                .editFirstContainer()
                .withImage(aggregate.getSpec().getImage())
                .withName("akces-commands")
                .withArgs(aggregate.getSpec().getArgs())
                .withResources(aggregate.getSpec().getResources())
                .endContainer()
                .editFirstVolume()
                .editConfigMap()
                .withName(commandServiceName + "-config")
                .endConfigMap()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        return statefulSet;
    }

}
