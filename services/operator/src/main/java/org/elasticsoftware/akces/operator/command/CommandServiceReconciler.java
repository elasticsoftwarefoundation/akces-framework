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

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

@Workflow(dependents = {
        @Dependent(type = ConfigMapDependentResource.class),
        @Dependent(type = StatefulSetDependentResource.class),
        @Dependent(type = ServiceDependentResource.class)
})
@ControllerConfiguration
public class CommandServiceReconciler implements Reconciler<CommandService> {
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public UpdateControl<CommandService> reconcile(CommandService commandService, Context<CommandService> context) throws Exception {
        return context.getSecondaryResource(StatefulSet.class).map(statefulSet -> {
            CommandService updatedCommandService = createCommandServiceForStatusUpdate(commandService, statefulSet);
            return UpdateControl.patchStatus(updatedCommandService);
        }).orElseGet(UpdateControl::noUpdate);
    }

    private CommandService createCommandServiceForStatusUpdate(CommandService commandService, StatefulSet statefulSet) {
        CommandService res = new CommandService();
        res.setMetadata(new ObjectMetaBuilder()
                .withName(commandService.getMetadata().getName())
                .withNamespace(commandService.getMetadata().getNamespace())
                .build());
        StatefulSetStatus statefulSetStatus =
                Objects.requireNonNullElse(statefulSet.getStatus(), new StatefulSetStatus());
        int readyReplicas = Objects.requireNonNullElse(statefulSetStatus.getReadyReplicas(), 0);
        CommandServiceStatus status = new CommandServiceStatus();
        status.setReadyReplicas(readyReplicas);
        res.setStatus(status);
        return res;
    }
}
