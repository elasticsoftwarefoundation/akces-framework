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

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Kubernetes Custom Resource definition for an AgenticAggregate.
 *
 * <p>An {@code AgenticAggregate} is a singleton Aggregate that integrates with AI agents (e.g. via
 * Spring AI MCP client). It is always deployed with exactly 1 replica and has its own dedicated
 * Kafka topics (Commands, DomainEvents, AggregateState) with a single partition each.
 *
 * <p>The CRD is registered under the API group {@code akces.elasticsoftwarefoundation.org},
 * API version {@code v1}, with the kind {@code AgenticAggregate} and short name {@code aag}.
 */
@Group("akces.elasticsoftwarefoundation.org")
@Version("v1")
@Kind("AgenticAggregate")
@ShortNames("aag")
public class AgenticAggregateResource extends CustomResource<AgenticAggregateSpec, AgenticAggregateStatus> implements Namespaced {
}
