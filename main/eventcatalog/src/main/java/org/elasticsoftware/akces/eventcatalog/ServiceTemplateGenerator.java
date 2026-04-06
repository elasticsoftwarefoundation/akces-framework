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

package org.elasticsoftware.akces.eventcatalog;

import java.util.List;

/**
 * Generates EventCatalog-compatible MDX service documentation from service metadata.
 * <p>
 * The generated output uses the EventCatalog MDX frontmatter format and includes
 * an architecture node graph for visual representation.
 */
public class ServiceTemplateGenerator {

    /**
     * Generates an MDX service documentation string from the given metadata.
     *
     * @param service the metadata describing the service (aggregate)
     * @return the MDX-formatted service documentation string
     */
    public static String generate(ServiceMetadata service) {
        // Format the owners list
        StringBuilder ownersBuilder = new StringBuilder();
        for (String owner : service.owners()) {
            ownersBuilder.append("    - ").append(owner).append("\n");
        }
        String ownersList = ownersBuilder.toString().stripTrailing();

        // Format the receives list
        StringBuilder receivesBuilder = new StringBuilder();
        if (service.receives().isEmpty()) {
            receivesBuilder.append("  []");
        } else {
            for (Message command : service.receives()) {
                receivesBuilder.append("  - id: ").append(command.id()).append("\n");
                receivesBuilder.append("    version: ").append(command.version()).append("\n");
            }
        }
        String receivesList = receivesBuilder.toString().stripTrailing();

        // Format the sends list
        StringBuilder sendsBuilder = new StringBuilder();
        if (service.sends().isEmpty()) {
            sendsBuilder.append("  []");
        } else {
            for (Message event : service.sends()) {
                sendsBuilder.append("  - id: ").append(event.id()).append("\n");
                sendsBuilder.append("    version: ").append(event.version()).append("\n");
            }
        }
        String sendsList = sendsBuilder.toString().stripTrailing();

        // Build optional type line for the frontmatter
        String typeLine = (service.type() != null && !service.type().isBlank())
                ? "type: " + service.type() + "\n"
                : "";

        // Create the template with string replacements
        return SERVICE_TEMPLATE
            .replace("#{service.id}", service.id())
            .replace("#{service.version}", service.version())
            .replace("#{service.name}", service.name())
            .replace("#{service.summary}", service.summary())
            .replace("#{typeLine}", typeLine)
            .replace("#{ownersList}", ownersList)
            .replace("#{receivesList}", receivesList)
            .replace("#{sendsList}", sendsList)
            .replace("#{service.language}", service.language())
            .replace("#{service.repositoryUrl}", service.repositoryUrl());
    }

    /** Represents a command or event message entry in the catalog. */
    public record Message(String id, String version) {
    }

    private static final String SERVICE_TEMPLATE = """
---
id: #{service.id}
version: #{service.version}
name: #{service.name}
summary: #{service.summary}
#{typeLine}owners:
#{ownersList}
receives:
#{receivesList}
sends:
#{sendsList}
repository:
  language: #{service.language}
  url: #{service.repositoryUrl}
---
import Footer from '@catalog/components/footer.astro';

## Architecture diagram

<NodeGraph />

<Footer />""";

    /**
     * Metadata describing a service (aggregate) for EventCatalog documentation generation.
     *
     * @param id            the unique service identifier (aggregate name)
     * @param version       the service version in semver format
     * @param name          the human-readable display name
     * @param summary       a brief description of the service
     * @param type          optional type tag (e.g. {@code "Singleton"} for agentic aggregates); may be null or blank
     * @param owners        the list of service owners
     * @param receives      the list of commands and events the service handles
     * @param sends         the list of events the service produces
     * @param language      the implementation language
     * @param repositoryUrl the URL to the service source in the repository
     */
    public record ServiceMetadata(
        String id,
        String version,
        String name,
        String summary,
        String type,
        List<String> owners,
        List<Message> receives,
        List<Message> sends,
        String language,
        String repositoryUrl
    ) {
        /**
         * Convenience constructor for standard aggregates without a type tag.
         *
         * @param id            the unique service identifier
         * @param version       the service version
         * @param name          the display name
         * @param summary       the service summary
         * @param owners        the service owners
         * @param receives      the commands/events this service receives
         * @param sends         the events this service sends
         * @param language      the implementation language
         * @param repositoryUrl the repository URL
         */
        public ServiceMetadata(
            String id,
            String version,
            String name,
            String summary,
            List<String> owners,
            List<Message> receives,
            List<Message> sends,
            String language,
            String repositoryUrl
        ) {
            this(id, version, name, summary, null, owners, receives, sends, language, repositoryUrl);
        }
    }
}