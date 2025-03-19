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

package org.elasticsoftware.akces.eventcatalog;

import java.util.List;

public class ServiceTemplateGenerator {

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

        // Create the template with string replacements

        return SERVICE_TEMPLATE
            .replace("#{service.id}", service.id())
            .replace("#{service.version}", service.version())
            .replace("#{service.name}", service.name())
            .replace("#{service.summary}", service.summary())
            .replace("#{ownersList}", ownersList)
            .replace("#{receivesList}", receivesList)
            .replace("#{sendsList}", sendsList)
            .replace("#{service.language}", service.language())
            .replace("#{service.repositoryUrl}", service.repositoryUrl());
    }

    // Define POJO classes for the data model
    public record Message(String id, String version) {
    }

    private static final String SERVICE_TEMPLATE = """
---
id: #{service.id}
version: #{service.version}
name: #{service.name}
summary: #{service.summary}
owners:
#{ownersList}
receives:
#{receivesList}
sends:
#{sendsList}
repository:
  language: #{service.language}
  url: #{service.repositoryUrl}
---""";

    public record ServiceMetadata(
        String id,
        String version,
        String name,
        String summary,
        List<String> owners,
        List<Message> receives,
        List<Message> sends,
        String language,
        String repositoryUrl
    ) {}
}