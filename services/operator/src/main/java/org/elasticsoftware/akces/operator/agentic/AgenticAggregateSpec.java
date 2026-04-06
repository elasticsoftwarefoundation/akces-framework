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

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ResourceRequirements;

import java.util.List;

/**
 * Specification for an {@link AgenticAggregateResource}.
 *
 * <p>Describes the desired state of an AgenticAggregate deployment, including the container image,
 * resource requirements, optional sidecar containers (e.g. MCP server sidecars), and additional
 * Spring application properties to merge into the deployed ConfigMap.
 *
 * <p>AgenticAggregates are always deployed as a single replica (singleton), so there is no
 * {@code replicas} field — the replica count is fixed at 1.
 */
public class AgenticAggregateSpec {

    /**
     * The container image to use for the AgenticAggregate service.
     */
    private String image;

    /**
     * The Spring application name ({@code SPRING_APPLICATION_NAME} env var) for the deployed pod.
     */
    private String applicationName;

    /**
     * Optional JVM / application arguments passed to the main container.
     */
    private List<String> args;

    /**
     * Kubernetes resource requirements (CPU and memory requests/limits) for the main container.
     */
    private ResourceRequirements resources;

    /**
     * Whether the AgenticAggregate service is allowed to overwrite existing Avro schemas in the
     * Schema Registry at startup. Defaults to {@code false}.
     */
    private boolean enableSchemaOverwrites = false;

    /**
     * Optional list of sidecar containers (e.g. MCP server proxies) to inject into the pod
     * alongside the main AgenticAggregate container.
     */
    private List<SidecarSpec> sidecars;

    /**
     * Additional environment variables to inject into the main AgenticAggregate container.
     * These are merged with the environment variables defined in the StatefulSet template.
     */
    private List<EnvVar> env;

    /**
     * Additional Spring {@code application.properties} content to append to the ConfigMap that is
     * mounted into the pod. Use this to supply service-specific configuration (e.g. MCP server
     * URLs, AI model settings) without baking them into the container image.
     */
    private String applicationProperties;

    /**
     * The name of the Kubernetes {@code Secret} used as an image pull secret for the pod.
     * When {@code null}, no image pull secret is added and the cluster's default credentials
     * (or public access) are used.
     */
    private String imagePullSecret;

    /**
     * The {@code storageClassName} for the PVC created by the StatefulSet's
     * {@code volumeClaimTemplate}. When {@code null}, no storage class is set and the cluster's
     * default {@code StorageClass} is applied automatically by Kubernetes.
     */
    private String storageClassName;

    /**
     * The size of the persistent volume claim (PVC) for the AgenticAggregate's data directory.
     * Expressed as a Kubernetes quantity string (e.g. {@code "4Gi"}, {@code "10Gi"}).
     * Defaults to {@code "4Gi"} when not set.
     */
    private String storageSize = "4Gi";

    /**
     * Returns the container image for the AgenticAggregate service.
     *
     * @return the image string
     */
    public String getImage() {
        return image;
    }

    /**
     * Sets the container image for the AgenticAggregate service.
     *
     * @param image the image string
     */
    public void setImage(String image) {
        this.image = image;
    }

    /**
     * Returns the Spring application name used as the {@code SPRING_APPLICATION_NAME} environment
     * variable in the deployed pod.
     *
     * @return the application name
     */
    public String getApplicationName() {
        return applicationName;
    }

    /**
     * Sets the Spring application name.
     *
     * @param applicationName the application name
     */
    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    /**
     * Returns the optional list of command-line arguments for the main container.
     *
     * @return the argument list, or {@code null} if not set
     */
    public List<String> getArgs() {
        return args;
    }

    /**
     * Sets the command-line arguments for the main container.
     *
     * @param args the argument list
     */
    public void setArgs(List<String> args) {
        this.args = args;
    }

    /**
     * Returns the Kubernetes resource requirements for the main container.
     *
     * @return the resource requirements, or {@code null} if not set
     */
    public ResourceRequirements getResources() {
        return resources;
    }

    /**
     * Sets the Kubernetes resource requirements for the main container.
     *
     * @param resources the resource requirements
     */
    public void setResources(ResourceRequirements resources) {
        this.resources = resources;
    }

    /**
     * Returns whether schema overwrites are enabled for the AgenticAggregate service.
     *
     * @return {@code true} if schema overwrites are enabled, {@code false} otherwise
     */
    public boolean isEnableSchemaOverwrites() {
        return enableSchemaOverwrites;
    }

    /**
     * Sets whether schema overwrites are enabled for the AgenticAggregate service.
     *
     * @param enableSchemaOverwrites {@code true} to enable schema overwrites
     */
    public void setEnableSchemaOverwrites(boolean enableSchemaOverwrites) {
        this.enableSchemaOverwrites = enableSchemaOverwrites;
    }

    /**
     * Returns the optional list of sidecar container specifications to inject into the pod.
     *
     * @return the list of {@link SidecarSpec}, or {@code null} if not set
     */
    public List<SidecarSpec> getSidecars() {
        return sidecars;
    }

    /**
     * Sets the list of sidecar container specifications to inject into the pod.
     *
     * @param sidecars the list of {@link SidecarSpec}
     */
    public void setSidecars(List<SidecarSpec> sidecars) {
        this.sidecars = sidecars;
    }

    /**
     * Returns additional environment variables to inject into the main container.
     *
     * @return the list of {@link EnvVar}, or {@code null} if not set
     */
    public List<EnvVar> getEnv() {
        return env;
    }

    /**
     * Sets additional environment variables to inject into the main container.
     *
     * @param env the list of {@link EnvVar}
     */
    public void setEnv(List<EnvVar> env) {
        this.env = env;
    }

    /**
     * Returns the additional {@code application.properties} content to append to the ConfigMap.
     *
     * @return the properties string, or {@code null} if not set
     */
    public String getApplicationProperties() {
        return applicationProperties;
    }

    /**
     * Sets the additional {@code application.properties} content to append to the ConfigMap.
     *
     * @param applicationProperties the properties string
     */
    public void setApplicationProperties(String applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    /**
     * Returns the name of the image pull secret used by the pod.
     *
     * @return the secret name, or {@code null} if no pull secret should be configured
     */
    public String getImagePullSecret() {
        return imagePullSecret;
    }

    /**
     * Sets the name of the image pull secret used by the pod. Set to {@code null} to rely on
     * cluster-level credentials or public image access.
     *
     * @param imagePullSecret the secret name, or {@code null}
     */
    public void setImagePullSecret(String imagePullSecret) {
        this.imagePullSecret = imagePullSecret;
    }

    /**
     * Returns the storage class name for the PVC created by the StatefulSet.
     *
     * @return the storage class name, or {@code null} if the cluster default should be used
     */
    public String getStorageClassName() {
        return storageClassName;
    }

    /**
     * Sets the storage class name for the PVC. Set to {@code null} to use the cluster's default
     * {@code StorageClass}.
     *
     * @param storageClassName the storage class name, or {@code null}
     */
    public void setStorageClassName(String storageClassName) {
        this.storageClassName = storageClassName;
    }

    /**
     * Returns the PVC storage size requested by the StatefulSet.
     *
     * @return the storage size as a Kubernetes quantity string (defaults to {@code "4Gi"})
     */
    public String getStorageSize() {
        return storageSize;
    }

    /**
     * Sets the PVC storage size. Use a Kubernetes quantity string (e.g. {@code "4Gi"},
     * {@code "10Gi"}).
     *
     * @param storageSize the storage size string
     */
    public void setStorageSize(String storageSize) {
        this.storageSize = storageSize;
    }
}
