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

package org.elasticsoftware.akces.codegen;

import org.elasticsoftware.akces.codegen.model.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Code generator that reads an event-modeling JSON definition (extended with Akces-specific
 * configuration) and generates Akces Framework aggregate source code.
 * <p>
 * The input format combines the
 * <a href="https://github.com/dilgerma/event-modeling-spec/blob/main/eventmodeling.schema.json">
 * event-modeling specification</a> with Akces-specific aggregate configuration.
 * <p>
 * For each aggregate found in the definition, the generator produces:
 * <ul>
 *     <li>Command records (in a {@code commands/} subpackage)</li>
 *     <li>Event records (in an {@code events/} subpackage)</li>
 *     <li>Aggregate state record</li>
 *     <li>Aggregate class with command handlers and event sourcing handlers</li>
 * </ul>
 */
public final class AkcesCodeGenerator {

    private static final String LICENSE_HEADER = """
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
            """;

    private final JsonMapper jsonMapper;
    private final String basePackage;

    /**
     * Creates a new code generator with the given base package.
     *
     * @param basePackage the base Java package for generated code (e.g., "com.example.aggregates")
     */
    public AkcesCodeGenerator(String basePackage) {
        this.basePackage = Objects.requireNonNull(basePackage, "basePackage must not be null");
        this.jsonMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    /**
     * Parses an event-modeling definition from an input stream.
     */
    public EventModelDefinition parse(InputStream inputStream) {
        return jsonMapper.readValue(inputStream, EventModelDefinition.class);
    }

    /**
     * Parses an event-modeling definition from a file path.
     */
    public EventModelDefinition parse(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        }
    }

    /**
     * Generates all Java source files from the given event-modeling definition.
     *
     * @param definition the parsed event-modeling definition
     * @return a list of generated source files
     */
    public List<GeneratedFile> generate(EventModelDefinition definition) {
        List<GeneratedFile> files = new ArrayList<>();

        // Group commands and events by aggregate
        Map<String, List<SliceData>> aggregateSlices = groupByAggregate(definition);

        for (Map.Entry<String, List<SliceData>> entry : aggregateSlices.entrySet()) {
            String aggregateName = entry.getKey();
            List<SliceData> slices = entry.getValue();
            AggregateConfig config = definition.aggregateConfig() != null
                    ? definition.aggregateConfig().get(aggregateName)
                    : null;

            files.addAll(generateAggregate(
                    basePackage,
                    aggregateName,
                    config,
                    slices));
        }

        return files;
    }

    /**
     * Generates all Java source files and writes them to the given output directory.
     *
     * @param definition the parsed event-modeling definition
     * @param outputDir  the root output directory for generated sources
     * @return a list of generated source files
     * @throws IOException if an I/O error occurs
     */
    public List<GeneratedFile> generateToDirectory(EventModelDefinition definition, Path outputDir) throws IOException {
        List<GeneratedFile> files = generate(definition);
        for (GeneratedFile file : files) {
            Path filePath = outputDir.resolve(file.relativePath());
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, file.content());
        }
        return files;
    }

    // --- Internal data structures ---

    private record SliceData(
            Slice slice,
            List<Element> commands,
            List<Element> successEvents,
            List<Element> errorEvents
    ) {
    }

    // --- Grouping logic ---

    private Map<String, List<SliceData>> groupByAggregate(EventModelDefinition definition) {
        Map<String, List<SliceData>> result = new LinkedHashMap<>();

        for (Slice slice : definition.slices()) {
            // Group commands and events by their aggregate
            Map<String, List<Element>> commandsByAggregate = slice.commands().stream()
                    .filter(e -> e.aggregate() != null)
                    .collect(Collectors.groupingBy(Element::aggregate, LinkedHashMap::new, Collectors.toList()));

            Map<String, List<Element>> eventsByAggregate = slice.events().stream()
                    .filter(e -> e.aggregate() != null)
                    .collect(Collectors.groupingBy(Element::aggregate, LinkedHashMap::new, Collectors.toList()));

            // Merge all aggregate names from both commands and events
            Set<String> aggregateNames = new LinkedHashSet<>();
            aggregateNames.addAll(commandsByAggregate.keySet());
            aggregateNames.addAll(eventsByAggregate.keySet());

            for (String aggregateName : aggregateNames) {
                List<Element> commands = commandsByAggregate.getOrDefault(aggregateName, List.of());
                List<Element> allEvents = eventsByAggregate.getOrDefault(aggregateName, List.of());
                List<Element> successEvents = allEvents.stream().filter(e -> !e.isError()).toList();
                List<Element> errorEvents = allEvents.stream().filter(Element::isError).toList();

                result.computeIfAbsent(aggregateName, k -> new ArrayList<>())
                        .add(new SliceData(slice, commands, successEvents, errorEvents));
            }
        }

        return result;
    }

    // --- Code generation ---

    private List<GeneratedFile> generateAggregate(
            String basePackage,
            String aggregateName,
            AggregateConfig config,
            List<SliceData> slices) {

        List<GeneratedFile> files = new ArrayList<>();
        String aggregateLower = aggregateName.substring(0, 1).toLowerCase() + aggregateName.substring(1);
        String aggregatePackage = basePackage + "." + aggregateLower;

        // Collect all unique commands and events across slices
        Map<String, Element> allCommands = new LinkedHashMap<>();
        Map<String, Element> allSuccessEvents = new LinkedHashMap<>();
        Map<String, Element> allErrorEvents = new LinkedHashMap<>();

        for (SliceData sliceData : slices) {
            for (Element cmd : sliceData.commands()) {
                allCommands.putIfAbsent(cmd.title(), cmd);
            }
            for (Element evt : sliceData.successEvents()) {
                allSuccessEvents.putIfAbsent(evt.title(), evt);
            }
            for (Element evt : sliceData.errorEvents()) {
                allErrorEvents.putIfAbsent(evt.title(), evt);
            }
        }

        // Generate command records
        for (Element cmd : allCommands.values()) {
            files.add(generateCommandRecord(aggregatePackage, cmd));
        }

        // Generate event records
        for (Element evt : allSuccessEvents.values()) {
            files.add(generateEventRecord(aggregatePackage, evt, false));
        }
        for (Element evt : allErrorEvents.values()) {
            files.add(generateEventRecord(aggregatePackage, evt, true));
        }

        // Generate state record
        if (config != null && !config.stateFields().isEmpty()) {
            files.add(generateStateRecord(aggregatePackage, aggregateName, config));
        }

        // Generate aggregate class
        files.add(generateAggregateClass(
                aggregatePackage, aggregateName, config, slices,
                allCommands, allSuccessEvents, allErrorEvents));

        return files;
    }

    // --- Command record generation ---

    private GeneratedFile generateCommandRecord(String aggregatePackage, Element command) {
        String className = command.title() + "Command";
        String commandsPackage = aggregatePackage + ".commands";
        String aggregateLower = aggregatePackage.substring(aggregatePackage.lastIndexOf('.') + 1);

        StringBuilder sb = new StringBuilder();
        sb.append(LICENSE_HEADER).append("\n");
        sb.append("package ").append(commandsPackage).append(";\n\n");

        // Collect imports
        Set<String> imports = new TreeSet<>();
        imports.add("org.elasticsoftware.akces.annotations.CommandInfo");
        imports.add("org.elasticsoftware.akces.commands.Command");

        boolean hasIdAttribute = command.fields().stream().anyMatch(Field::isIdAttribute);
        if (hasIdAttribute) {
            imports.add("org.elasticsoftware.akces.annotations.AggregateIdentifier");
        }

        boolean hasRequired = command.fields().stream().anyMatch(f -> !f.isOptional());
        boolean hasOptional = command.fields().stream().anyMatch(Field::isOptional);
        boolean hasPii = command.fields().stream().anyMatch(Field::isPiiData);
        if (hasRequired) {
            imports.add("jakarta.validation.constraints.NotNull");
        }
        if (hasOptional) {
            imports.add("jakarta.annotation.Nullable");
        }
        if (hasPii) {
            imports.add("org.elasticsoftware.akces.annotations.PIIData");
        }

        collectFieldTypeImports(command.fields(), imports);

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Annotation
        int version = 1;
        sb.append("@CommandInfo(type = \"").append(command.title()).append("\", version = ").append(version).append(")\n");

        // Record declaration
        sb.append("public record ").append(className).append("(\n");

        List<Field> fields = command.fields();
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            sb.append("        ");
            appendFieldAnnotations(sb, field);
            sb.append(mapFieldType(field)).append(" ").append(field.name());
            if (i < fields.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }

        sb.append(") implements Command {\n");

        // getAggregateId method
        String idFieldName = findIdFieldName(fields);
        sb.append("    @Override\n");
        sb.append("    public String getAggregateId() {\n");
        sb.append("        return ").append(idFieldName).append("();\n");
        sb.append("    }\n");
        sb.append("}\n");

        String relativePath = commandsPackage.replace('.', '/') + "/" + className + ".java";
        return new GeneratedFile(relativePath, sb.toString());
    }

    // --- Event record generation ---

    private GeneratedFile generateEventRecord(String aggregatePackage, Element event, boolean isError) {
        String className = event.title() + "Event";
        String eventsPackage = aggregatePackage + ".events";
        String aggregateLower = aggregatePackage.substring(aggregatePackage.lastIndexOf('.') + 1);

        StringBuilder sb = new StringBuilder();
        sb.append(LICENSE_HEADER).append("\n");
        sb.append("package ").append(eventsPackage).append(";\n\n");

        // Collect imports
        Set<String> imports = new TreeSet<>();
        imports.add("org.elasticsoftware.akces.annotations.DomainEventInfo");
        if (isError) {
            imports.add("org.elasticsoftware.akces.events.ErrorEvent");
        } else {
            imports.add("org.elasticsoftware.akces.events.DomainEvent");
        }

        boolean hasIdAttribute = event.fields().stream().anyMatch(Field::isIdAttribute);
        if (hasIdAttribute) {
            imports.add("org.elasticsoftware.akces.annotations.AggregateIdentifier");
        }

        boolean hasRequired = event.fields().stream().anyMatch(f -> !f.isOptional());
        boolean hasOptional = event.fields().stream().anyMatch(Field::isOptional);
        boolean hasPii = event.fields().stream().anyMatch(Field::isPiiData);
        if (hasRequired) {
            imports.add("jakarta.validation.constraints.NotNull");
        }
        if (hasOptional) {
            imports.add("jakarta.annotation.Nullable");
        }
        if (hasPii) {
            imports.add("org.elasticsoftware.akces.annotations.PIIData");
        }

        collectFieldTypeImports(event.fields(), imports);

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Annotation
        sb.append("@DomainEventInfo(type = \"").append(event.title()).append("\")\n");

        // Record declaration
        sb.append("public record ").append(className).append("(\n");

        List<Field> fields = event.fields();
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            sb.append("        ");
            appendFieldAnnotations(sb, field);
            sb.append(mapFieldType(field)).append(" ").append(field.name());
            if (i < fields.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }

        String interfaceName = isError ? "ErrorEvent" : "DomainEvent";
        sb.append(") implements ").append(interfaceName).append(" {\n");

        // getAggregateId method
        String idFieldName = findIdFieldName(fields);
        sb.append("    @Override\n");
        sb.append("    public String getAggregateId() {\n");
        sb.append("        return ").append(idFieldName).append("();\n");
        sb.append("    }\n");
        sb.append("}\n");

        String relativePath = eventsPackage.replace('.', '/') + "/" + className + ".java";
        return new GeneratedFile(relativePath, sb.toString());
    }

    // --- State record generation ---

    private GeneratedFile generateStateRecord(String aggregatePackage, String aggregateName, AggregateConfig config) {
        String className = aggregateName + "State";
        String aggregateLower = aggregatePackage.substring(aggregatePackage.lastIndexOf('.') + 1);

        StringBuilder sb = new StringBuilder();
        sb.append(LICENSE_HEADER).append("\n");
        sb.append("package ").append(aggregatePackage).append(";\n\n");

        // Collect imports
        Set<String> imports = new TreeSet<>();
        imports.add("org.elasticsoftware.akces.aggregate.AggregateState");
        imports.add("org.elasticsoftware.akces.annotations.AggregateStateInfo");

        boolean hasIdAttribute = config.stateFields().stream().anyMatch(StateField::idAttribute);
        if (hasIdAttribute) {
            imports.add("org.elasticsoftware.akces.annotations.AggregateIdentifier");
        }

        boolean hasRequired = config.stateFields().stream().anyMatch(f -> !f.optional());
        boolean hasOptional = config.stateFields().stream().anyMatch(StateField::optional);
        boolean hasPii = config.stateFields().stream().anyMatch(StateField::piiData);

        if (hasRequired) {
            imports.add("jakarta.validation.constraints.NotNull");
        }
        if (hasOptional) {
            imports.add("jakarta.annotation.Nullable");
        }
        if (hasPii) {
            imports.add("org.elasticsoftware.akces.annotations.PIIData");
        }

        collectStateFieldTypeImports(config.stateFields(), imports);

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Annotation
        sb.append("@AggregateStateInfo(type = \"").append(aggregateName).append("\", version = ")
                .append(config.stateVersion()).append(")\n");

        // Record declaration
        sb.append("public record ").append(className).append("(\n");

        List<StateField> fields = config.stateFields();
        for (int i = 0; i < fields.size(); i++) {
            StateField field = fields.get(i);
            sb.append("        ");
            appendStateFieldAnnotations(sb, field);
            sb.append(mapType(field.type())).append(" ").append(field.name());
            if (i < fields.size() - 1) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }

        sb.append(") implements AggregateState {\n");

        // getAggregateId method
        String idFieldName = findStateIdFieldName(fields);
        sb.append("    @Override\n");
        sb.append("    public String getAggregateId() {\n");
        sb.append("        return ").append(idFieldName).append("();\n");
        sb.append("    }\n");
        sb.append("}\n");

        String relativePath = aggregatePackage.replace('.', '/') + "/" + className + ".java";
        return new GeneratedFile(relativePath, sb.toString());
    }

    // --- Aggregate class generation ---

    private GeneratedFile generateAggregateClass(
            String aggregatePackage,
            String aggregateName,
            AggregateConfig config,
            List<SliceData> slices,
            Map<String, Element> allCommands,
            Map<String, Element> allSuccessEvents,
            Map<String, Element> allErrorEvents) {

        String stateClassName = aggregateName + "State";
        String aggregateLower = aggregatePackage.substring(aggregatePackage.lastIndexOf('.') + 1);

        StringBuilder sb = new StringBuilder();
        sb.append(LICENSE_HEADER).append("\n");
        sb.append("package ").append(aggregatePackage).append(";\n\n");

        // Collect imports
        Set<String> imports = new TreeSet<>();
        imports.add("org.elasticsoftware.akces.aggregate.Aggregate");
        imports.add("org.elasticsoftware.akces.annotations.AggregateInfo");
        imports.add("org.elasticsoftware.akces.annotations.CommandHandler");
        imports.add("org.elasticsoftware.akces.annotations.EventSourcingHandler");
        imports.add("org.elasticsoftware.akces.events.DomainEvent");
        imports.add("jakarta.validation.constraints.NotNull");
        imports.add("java.util.stream.Stream");

        // Import all command classes
        for (String cmdTitle : allCommands.keySet()) {
            imports.add(aggregatePackage + ".commands." + cmdTitle + "Command");
        }

        // Import all event classes
        for (String evtTitle : allSuccessEvents.keySet()) {
            imports.add(aggregatePackage + ".events." + evtTitle + "Event");
        }
        for (String evtTitle : allErrorEvents.keySet()) {
            imports.add(aggregatePackage + ".events." + evtTitle + "Event");
        }

        for (String imp : imports) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");

        // Aggregate annotation
        sb.append("@AggregateInfo(\n");
        sb.append("        value = \"").append(aggregateName).append("\",\n");
        sb.append("        stateClass = ").append(stateClassName).append(".class");
        if (config != null && config.generateGDPRKeyOnCreate()) {
            sb.append(",\n        generateGDPRKeyOnCreate = true");
        }
        if (config != null && config.indexed()) {
            sb.append(",\n        indexed = true");
            if (config.indexName() != null) {
                sb.append(",\n        indexName = \"").append(config.indexName()).append("\"");
            }
        }
        sb.append(")\n");

        // Class declaration
        sb.append("public final class ").append(aggregateName).append(" implements Aggregate<").append(stateClassName).append("> {\n\n");

        // getName() method
        sb.append("    @Override\n");
        sb.append("    public String getName() {\n");
        sb.append("        return \"").append(aggregateName).append("\";\n");
        sb.append("    }\n\n");

        // getStateClass() method
        sb.append("    @Override\n");
        sb.append("    public Class<").append(stateClassName).append("> getStateClass() {\n");
        sb.append("        return ").append(stateClassName).append(".class;\n");
        sb.append("    }\n");

        // Generate command handlers and event sourcing handlers per slice
        Set<String> generatedCommandHandlers = new LinkedHashSet<>();
        Set<String> generatedEventSourcingHandlers = new LinkedHashSet<>();

        for (SliceData sliceData : slices) {
            for (Element cmd : sliceData.commands()) {
                if (generatedCommandHandlers.contains(cmd.title())) {
                    continue;
                }
                generatedCommandHandlers.add(cmd.title());

                // Find events produced by this command (events in the same slice)
                List<Element> successEvts = sliceData.successEvents();
                List<String> producesEvents = successEvts.stream()
                        .map(e -> e.title() + "Event.class")
                        .toList();
                List<String> errorEventNames = sliceData.errorEvents().stream()
                        .map(e -> e.title() + "Event.class")
                        .toList();

                boolean creates = cmd.createsAggregate() != null && cmd.createsAggregate();
                sb.append("\n");
                sb.append(generateCommandHandler(
                        aggregateName, stateClassName, cmd, creates, producesEvents, errorEventNames, successEvts));
            }

            // Generate event sourcing handlers for success events
            for (Element evt : sliceData.successEvents()) {
                if (generatedEventSourcingHandlers.contains(evt.title())) {
                    continue;
                }
                generatedEventSourcingHandlers.add(evt.title());

                boolean creates = evt.createsAggregate() != null && evt.createsAggregate();
                sb.append("\n");
                sb.append(generateEventSourcingHandler(stateClassName, evt, creates, config));
            }
        }

        sb.append("}\n");

        String relativePath = aggregatePackage.replace('.', '/') + "/" + aggregateName + ".java";
        return new GeneratedFile(relativePath, sb.toString());
    }

    private String generateCommandHandler(
            String aggregateName,
            String stateClassName,
            Element command,
            boolean creates,
            List<String> producesEvents,
            List<String> errorEvents,
            List<Element> successEventElements) {

        StringBuilder sb = new StringBuilder();
        String cmdClassName = command.title() + "Command";
        String methodName = creates ? "create" : toCamelCase(command.title());

        // Annotation
        sb.append("    @CommandHandler(");
        if (creates) {
            sb.append("create = true, ");
        }
        sb.append("produces = ");
        if (producesEvents.size() == 1) {
            sb.append(producesEvents.getFirst());
        } else {
            sb.append("{").append(String.join(", ", producesEvents)).append("}");
        }
        sb.append(", errors = {");
        sb.append(String.join(", ", errorEvents));
        sb.append("})\n");

        // Method signature
        sb.append("    public @NotNull Stream<DomainEvent> ").append(methodName).append("(");
        sb.append("@NotNull ").append(cmdClassName).append(" cmd, ");
        if (creates) {
            sb.append(stateClassName).append(" isNull");
        } else {
            sb.append("@NotNull ").append(stateClassName).append(" currentState");
        }
        sb.append(") {\n");
        sb.append("        // Command handler for aggregate: ").append(aggregateName).append("\n");

        // Method body - generate event construction
        if (producesEvents.size() == 1 && successEventElements.size() == 1) {
            Element event = successEventElements.getFirst();
            String eventClassName = producesEvents.getFirst().replace(".class", "");
            sb.append("        // TODO: Implement command handler logic\n");
            sb.append("        return Stream.of(new ").append(eventClassName).append("(");
            sb.append(generateEventConstructorArgsFromCommand(command, event));
            sb.append("));\n");
        } else {
            sb.append("        // TODO: Implement command handler logic\n");
            sb.append("        return Stream.of(\n");
            for (int i = 0; i < producesEvents.size(); i++) {
                String eventClassName = producesEvents.get(i).replace(".class", "");
                sb.append("                new ").append(eventClassName).append("(/* TODO */)");
                if (i < producesEvents.size() - 1) {
                    sb.append(",\n");
                } else {
                    sb.append("\n");
                }
            }
            sb.append("        );\n");
        }
        sb.append("    }\n");

        return sb.toString();
    }

    private String generateEventSourcingHandler(
            String stateClassName,
            Element event,
            boolean creates,
            AggregateConfig config) {

        StringBuilder sb = new StringBuilder();
        String eventClassName = event.title() + "Event";
        String methodName = creates ? "create" : toCamelCase(event.title());

        // Annotation
        sb.append("    @EventSourcingHandler");
        if (creates) {
            sb.append("(create = true)");
        }
        sb.append("\n");

        // Method signature
        sb.append("    public @NotNull ").append(stateClassName).append(" ").append(methodName).append("(");
        sb.append("@NotNull ").append(eventClassName).append(" event, ");
        if (creates) {
            sb.append(stateClassName).append(" isNull");
        } else {
            sb.append("@NotNull ").append(stateClassName).append(" state");
        }
        sb.append(") {\n");

        // Method body
        if (creates && config != null) {
            sb.append("        return new ").append(stateClassName).append("(");
            sb.append(generateStateConstructorArgsFromEvent(config, event));
            sb.append(");\n");
        } else {
            sb.append("        // TODO: Implement state transition logic\n");
            sb.append("        return state;\n");
        }
        sb.append("    }\n");

        return sb.toString();
    }

    // --- Helper methods ---

    /**
     * Generates event constructor arguments by mapping event fields from command fields.
     * Fields present in both command and event are mapped as {@code cmd.fieldName()}.
     * Fields in the event but not in the command get a typed default value placeholder.
     */
    private String generateEventConstructorArgsFromCommand(Element command, Element event) {
        Set<String> commandFieldNames = command.fields().stream()
                .map(Field::name)
                .collect(Collectors.toSet());

        return event.fields().stream()
                .map(eventField -> {
                    if (commandFieldNames.contains(eventField.name())) {
                        return "cmd." + eventField.name() + "()";
                    } else {
                        // Generate a typed default for fields not available from the command
                        return getDefaultValue(eventField);
                    }
                })
                .collect(Collectors.joining(", "));
    }

    /**
     * Returns a sensible default value expression for a field type to use in scaffolding code.
     */
    private String getDefaultValue(Field field) {
        String type = field.type();
        if (type == null) {
            return "null /* TODO: " + field.name() + " */";
        }
        return switch (type) {
            case "String", "UUID" -> "null /* TODO: " + field.name() + " */";
            case "Boolean" -> "false /* TODO: " + field.name() + " */";
            case "Double" -> "0.0 /* TODO: " + field.name() + " */";
            case "Decimal" -> "java.math.BigDecimal.ZERO /* TODO: " + field.name() + " */";
            case "Long" -> "0L /* TODO: " + field.name() + " */";
            case "Int" -> "0 /* TODO: " + field.name() + " */";
            default -> "null /* TODO: " + field.name() + " */";
        };
    }

    private String generateStateConstructorArgsFromEvent(AggregateConfig config, Element event) {
        List<StateField> stateFields = config.stateFields();
        Set<String> eventFieldNames = event.fields().stream()
                .map(Field::name)
                .collect(Collectors.toSet());

        return stateFields.stream()
                .map(sf -> {
                    if (eventFieldNames.contains(sf.name())) {
                        return "event." + sf.name() + "()";
                    } else {
                        return "null /* " + sf.name() + " */";
                    }
                })
                .collect(Collectors.joining(", "));
    }

    private void appendFieldAnnotations(StringBuilder sb, Field field) {
        if (field.isIdAttribute()) {
            sb.append("@AggregateIdentifier ");
        }
        if (field.isPiiData()) {
            sb.append("@PIIData ");
        }
        if (field.isOptional()) {
            sb.append("@Nullable ");
        } else {
            sb.append("@NotNull ");
        }
    }

    private void appendStateFieldAnnotations(StringBuilder sb, StateField field) {
        if (field.idAttribute()) {
            sb.append("@AggregateIdentifier ");
        }
        if (field.piiData()) {
            sb.append("@PIIData ");
        }
        if (field.optional()) {
            sb.append("@Nullable ");
        } else {
            sb.append("@NotNull ");
        }
    }

    private void collectFieldTypeImports(List<Field> fields, Set<String> imports) {
        for (Field field : fields) {
            String javaType = mapFieldType(field);
            if (javaType.equals("BigDecimal")) {
                imports.add("java.math.BigDecimal");
            } else if (javaType.equals("LocalDate")) {
                imports.add("java.time.LocalDate");
            } else if (javaType.equals("LocalDateTime")) {
                imports.add("java.time.LocalDateTime");
            }
            if (field.isList()) {
                imports.add("java.util.List");
            }
            if (!field.subfields().isEmpty()) {
                collectFieldTypeImports(field.subfields(), imports);
            }
        }
    }

    private void collectStateFieldTypeImports(List<StateField> fields, Set<String> imports) {
        for (StateField field : fields) {
            String javaType = mapType(field.type());
            if (javaType.equals("BigDecimal")) {
                imports.add("java.math.BigDecimal");
            } else if (javaType.equals("LocalDate")) {
                imports.add("java.time.LocalDate");
            } else if (javaType.equals("LocalDateTime")) {
                imports.add("java.time.LocalDateTime");
            }
        }
    }

    /**
     * Maps an event-modeling field type to a Java type, handling list cardinality.
     */
    static String mapFieldType(Field field) {
        String baseType = mapType(field.type());
        if (field.isList()) {
            return "List<" + baseType + ">";
        }
        return baseType;
    }

    /**
     * Maps an event-modeling type name to a Java type.
     */
    static String mapType(String type) {
        if (type == null) {
            throw new IllegalArgumentException("Field type must not be null");
        }
        return switch (type) {
            case "String", "UUID" -> "String";
            case "Boolean" -> "Boolean";
            case "Double" -> "Double";
            case "Decimal" -> "BigDecimal";
            case "Long" -> "Long";
            case "Int" -> "Integer";
            case "Date" -> "LocalDate";
            case "DateTime" -> "LocalDateTime";
            case "Custom" -> "Object";
            default -> throw new IllegalArgumentException("Unknown field type: " + type);
        };
    }

    private String findIdFieldName(List<Field> fields) {
        return fields.stream()
                .filter(Field::isIdAttribute)
                .map(Field::name)
                .findFirst()
                .orElse(fields.isEmpty() ? "id" : fields.getFirst().name());
    }

    private String findStateIdFieldName(List<StateField> fields) {
        return fields.stream()
                .filter(StateField::idAttribute)
                .map(StateField::name)
                .findFirst()
                .orElse(fields.isEmpty() ? "id" : fields.getFirst().name());
    }

    private String toCamelCase(String title) {
        if (title == null || title.isEmpty()) return title;
        // Convert PascalCase title like "CreditWallet" to camelCase "creditWallet"
        return title.substring(0, 1).toLowerCase() + title.substring(1);
    }

    /**
     * Generates all files to the given output directory from a JSON input stream.
     */
    public List<GeneratedFile> generateFromStream(InputStream inputStream, Path outputDir) throws IOException {
        EventModelDefinition definition = parse(inputStream);
        return generateToDirectory(definition, outputDir);
    }
}
