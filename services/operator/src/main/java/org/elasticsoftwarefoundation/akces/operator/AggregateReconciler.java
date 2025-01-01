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

package org.elasticsoftwarefoundation.akces.operator;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import org.elasticsoftwarefoundation.akces.operator.customresource.Aggregate;
import org.elasticsoftwarefoundation.akces.operator.customresource.AggregateStatus;
import org.elasticsoftwarefoundation.akces.operator.dependentresources.ServiceDependentResource;
import org.elasticsoftwarefoundation.akces.operator.dependentresources.StatefulSetDependentResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

@Workflow(dependents = {
        @Dependent(type = StatefulSetDependentResource.class),
        @Dependent(type = ServiceDependentResource.class)
})
@ControllerConfiguration
public class AggregateReconciler implements Reconciler<Aggregate> {
    private final Logger log = LoggerFactory.getLogger(getClass());
    @Override
    public UpdateControl<Aggregate> reconcile(Aggregate resource, Context<Aggregate> context) throws Exception {
        return context.getSecondaryResource(StatefulSet.class).map(statefulSet -> {
            Aggregate updatedAggregate = createAggregateForStatusUpdate(resource, statefulSet);
            log.info(
                    "Updating status of Tomcat {} in namespace {} to {} ready replicas",
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
        StatefulSetStatus deploymentStatus =
                Objects.requireNonNullElse(statefulSet.getStatus(), new StatefulSetStatus());
        int readyReplicas = Objects.requireNonNullElse(deploymentStatus.getReadyReplicas(), 0);
        AggregateStatus status = new AggregateStatus();
        status.setReadyReplicas(readyReplicas);
        res.setStatus(status);
        return res;
    }
}
