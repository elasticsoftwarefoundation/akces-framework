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

package org.elasticsoftware.akces.agentic.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Spring Boot auto-configuration marker for the Akces Agentic Aggregate module.
 *
 * <p>The actual agentic aggregate runtime beans ({@code AgenticAggregatePartition} and
 * {@code AgenticAggregateRuntime}) are registered dynamically by
 * {@link org.elasticsoftware.akces.agentic.beans.AgenticAggregateBeanFactoryPostProcessor},
 * which scans for {@link org.elasticsoftware.akces.annotations.AgenticAggregateInfo}-annotated
 * beans and creates the corresponding runtime infrastructure at application-context
 * initialisation time — exactly as
 * {@link org.elasticsoftware.akces.beans.AggregateBeanFactoryPostProcessor} does for regular
 * aggregates.
 *
 * <p>The Kafka factory beans required by the post-processor are provided by
 * {@link org.elasticsoftware.akces.agentic.runtime.AgenticAggregateServiceApplication}.
 *
 * <p>This configuration is automatically imported via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@AutoConfiguration
public class AgenticAggregateAutoConfiguration {
}


