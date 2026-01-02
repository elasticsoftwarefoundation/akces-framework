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

package org.elasticsoftware.akces.operator.query;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.javaoperatorsdk.operator.ReconcilerUtils;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import java.util.Collections;
import java.util.Map;

@KubernetesDependent(informer = @Informer(
        labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class ConfigMapDependentResource extends CRUDKubernetesDependentResource<ConfigMap, QueryService> {

    public ConfigMapDependentResource() {
        super(ConfigMap.class);
    }

    @Override
    protected ConfigMap desired(QueryService queryService, Context<QueryService> context) {
        final ObjectMeta queryServiceMetadata = queryService.getMetadata();
        final String queryServiceName = queryServiceMetadata.getName();
        final QueryServiceSpec spec = queryService.getSpec();
        return new ConfigMapBuilder(ReconcilerUtils.loadYaml(ConfigMap.class, getClass(), "configmap.yaml"))
                .editMetadata()
                .withName(queryServiceName + "-config")
                .withNamespace(queryServiceMetadata.getNamespace())
                .addToLabels("app.kubernetes.io/part-of", queryServiceName)
                .addToLabels("app.kubernetes.io/managed-by", "akces-operator")
                .endMetadata()
                .addToData(spec.getApplicationProperties() != null ?
                        Map.of("application.properties", spec.getApplicationProperties()) :
                        Collections.emptyMap())
                .build();
    }
}