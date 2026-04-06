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

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ResourceRequirements;

import java.util.List;

/**
 * Specification for a sidecar container to be injected alongside the main AgenticAggregate
 * container.
 *
 * <p>Sidecars are commonly used to run MCP (Model Context Protocol) server proxies or other
 * auxiliary services that the AgenticAggregate needs to communicate with at localhost. Each
 * {@code SidecarSpec} maps directly to a Kubernetes {@code Container} object added to the pod's
 * container list.
 */
public class SidecarSpec {

    /**
     * The name of the sidecar container. Must be unique within the pod.
     */
    private String name;

    /**
     * The container image for the sidecar.
     */
    private String image;

    /**
     * Environment variables to set in the sidecar container.
     */
    private List<EnvVar> env;

    /**
     * Container ports to expose from the sidecar.
     */
    private List<ContainerPort> ports;

    /**
     * Kubernetes resource requirements (CPU and memory requests/limits) for the sidecar container.
     */
    private ResourceRequirements resources;

    /**
     * Kubernetes readiness probe for the sidecar container.
     */
    private Probe readinessProbe;

    /**
     * Kubernetes liveness probe for the sidecar container.
     */
    private Probe livenessProbe;

    /**
     * Returns the name of the sidecar container.
     *
     * @return the container name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of the sidecar container.
     *
     * @param name the container name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the container image for the sidecar.
     *
     * @return the image string
     */
    public String getImage() {
        return image;
    }

    /**
     * Sets the container image for the sidecar.
     *
     * @param image the image string
     */
    public void setImage(String image) {
        this.image = image;
    }

    /**
     * Returns the environment variables for the sidecar container.
     *
     * @return the list of {@link EnvVar}, or {@code null} if not set
     */
    public List<EnvVar> getEnv() {
        return env;
    }

    /**
     * Sets the environment variables for the sidecar container.
     *
     * @param env the list of {@link EnvVar}
     */
    public void setEnv(List<EnvVar> env) {
        this.env = env;
    }

    /**
     * Returns the container ports to expose from the sidecar.
     *
     * @return the list of {@link ContainerPort}, or {@code null} if not set
     */
    public List<ContainerPort> getPorts() {
        return ports;
    }

    /**
     * Sets the container ports to expose from the sidecar.
     *
     * @param ports the list of {@link ContainerPort}
     */
    public void setPorts(List<ContainerPort> ports) {
        this.ports = ports;
    }

    /**
     * Returns the resource requirements for the sidecar container.
     *
     * @return the {@link ResourceRequirements}, or {@code null} if not set
     */
    public ResourceRequirements getResources() {
        return resources;
    }

    /**
     * Sets the resource requirements for the sidecar container.
     *
     * @param resources the {@link ResourceRequirements}
     */
    public void setResources(ResourceRequirements resources) {
        this.resources = resources;
    }

    /**
     * Returns the readiness probe for the sidecar container.
     *
     * @return the {@link Probe}, or {@code null} if not set
     */
    public Probe getReadinessProbe() {
        return readinessProbe;
    }

    /**
     * Sets the readiness probe for the sidecar container.
     *
     * @param readinessProbe the {@link Probe}
     */
    public void setReadinessProbe(Probe readinessProbe) {
        this.readinessProbe = readinessProbe;
    }

    /**
     * Returns the liveness probe for the sidecar container.
     *
     * @return the {@link Probe}, or {@code null} if not set
     */
    public Probe getLivenessProbe() {
        return livenessProbe;
    }

    /**
     * Sets the liveness probe for the sidecar container.
     *
     * @param livenessProbe the {@link Probe}
     */
    public void setLivenessProbe(Probe livenessProbe) {
        this.livenessProbe = livenessProbe;
    }
}
