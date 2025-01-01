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

package org.elasticsoftwarefoundation.akces.operator.dependentresources;

import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import org.elasticsoftwarefoundation.akces.operator.customresource.Aggregate;

@KubernetesDependent(informer = @Informer(
        labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class StatefulsetDependentResource extends CRUDKubernetesDependentResource<StatefulSet, Aggregate> {
    public StatefulsetDependentResource() {
        super(StatefulSet.class);
    }
}
