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

package org.elasticsoftware.akces.operator.aggregate;

import io.fabric8.kubernetes.api.model.ResourceRequirements;

import java.util.List;

public class AggregateSpec {
    private Integer replicas;
    private String image;
    private List<String> aggregateNames;
    private List<String> args;
    private ResourceRequirements resources;
    private Boolean enableSchemaOverwrites = Boolean.FALSE;
    private String applicationName;

    public List<String> getAggregateNames() {
        return aggregateNames;
    }

    public void setAggregateNames(List<String> aggregateNames) {
        this.aggregateNames = aggregateNames;
    }

    public Integer getReplicas() {
        return replicas;
    }

    public void setReplicas(Integer replicas) {
        this.replicas = replicas;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public ResourceRequirements getResources() {
        return resources;
    }

    public void setResources(ResourceRequirements resources) {
        this.resources = resources;
    }

    public Boolean getEnableSchemaOverwrites() {
        return enableSchemaOverwrites;
    }

    public void setEnableSchemaOverwrites(Boolean enableSchemaOverwrites) {
        this.enableSchemaOverwrites = enableSchemaOverwrites;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }
}
