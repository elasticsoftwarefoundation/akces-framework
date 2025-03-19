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

public class MessageTemplateGenerator {

        // Define POJO classes for the data model
        public record EventMetadata(
            String id,
            String name,
            String version,
            String summary,
            List<String> owners,
            String schemaPath,
            String language,
            String repositoryUrl
        ) {}

        private static final String EVENT_TEMPLATE = """
    ---
    id: %s
    name: %s
    version: %s
    summary: %s
    owners:
    %s
    schemaPath: '%s'
    repository:
        language: %s
        url: %s
    ---""";

        public static String generate(EventMetadata event) {
            // Format the owners list
            StringBuilder ownersBuilder = new StringBuilder();
            for (String owner : event.owners()) {
                ownersBuilder.append("    - ").append(owner).append("\n");
            }
            String ownersList = ownersBuilder.toString().stripTrailing();

            // Create the template with string formatting
            return String.format(
                EVENT_TEMPLATE,
                event.id(),
                event.name(),
                event.version(),
                event.summary(),
                ownersList,
                event.schemaPath(),
                event.language(),
                event.repositoryUrl()
            );
        }
    }