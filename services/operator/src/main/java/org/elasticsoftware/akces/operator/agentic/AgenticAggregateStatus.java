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

/**
 * Observed status of an {@link AgenticAggregateResource}.
 *
 * <p>Reflects the current number of ready replicas as reported by the underlying
 * {@code StatefulSet}. Because AgenticAggregates are singletons this value will typically be
 * either {@code 0} (not yet ready) or {@code 1} (ready).
 */
public class AgenticAggregateStatus {

    /**
     * The number of replicas that are currently ready to serve traffic. Defaults to {@code 0}.
     */
    private Integer readyReplicas = 0;

    /**
     * Returns the number of ready replicas.
     *
     * @return the ready replica count
     */
    public Integer getReadyReplicas() {
        return readyReplicas;
    }

    /**
     * Sets the number of ready replicas.
     *
     * @param readyReplicas the ready replica count
     */
    public void setReadyReplicas(Integer readyReplicas) {
        this.readyReplicas = readyReplicas;
    }
}
