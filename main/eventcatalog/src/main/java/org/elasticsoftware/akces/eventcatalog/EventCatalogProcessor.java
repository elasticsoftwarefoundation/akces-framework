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

import org.elasticsoftware.akces.annotations.*;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Stream;

@SupportedAnnotationTypes(
        {
                "org.elasticsoftware.akces.annotations.CommandInfo",
                "org.elasticsoftware.akces.annotations.DomainEventInfo",
                "org.elasticsoftware.akces.annotations.AggregateInfo",
                "org.elasticsoftware.akces.annotations.CommandHandler",
                "org.elasticsoftware.akces.annotations.EventHandler"
        })
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class EventCatalogProcessor extends AbstractProcessor {

    private final Map<CommandInfo, TypeElement> commandCache = new HashMap<>();
    private final Map<DomainEventInfo, TypeElement> eventCache = new HashMap<>();
    private final Map<AggregateInfo, TypeElement> aggregateCache = new HashMap<>();
    private final Map<CommandHandler, Element> commandHandlerCache = new HashMap<>();
    private final Map<EventHandler, Element> eventHandlerCache = new HashMap<>();
    // Maps to store the relationships between aggregates and handlers
    private final Map<AggregateInfo, List<CommandHandler>> aggregateToCommandHandlers = new HashMap<>();
    private final Map<AggregateInfo, List<EventHandler>> aggregateToEventHandlers = new HashMap<>();
    // Maps to store produced events and error events for each aggregate
    Map<AggregateInfo, Set<TypeElement>> aggregateProducedEvents = new HashMap<>();
    Map<AggregateInfo, Set<TypeElement>> aggregateErrorEvents = new HashMap<>();
    Map<AggregateInfo, Set<TypeElement>> aggregateHandledCommands = new HashMap<>();
    // Create reverse lookup maps to find annotation details
    Map<TypeElement, AnnotationMirror> commandAnnotations = new HashMap<>();
    Map<TypeElement, AnnotationMirror> eventAnnotations = new HashMap<>();

    private boolean processed = false;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processed) {
            return true;
        }

        for (TypeElement annotation : annotations) {
            String annotationName = annotation.getQualifiedName().toString();
            if (CommandInfo.class.getCanonicalName().equals(annotationName)) {
                processCommandInfoAnnotations(roundEnv);
            } else if (DomainEventInfo.class.getCanonicalName().equals(annotationName)) {
                processDomainEventInfoAnnotations(roundEnv);
            } else if (AggregateInfo.class.getCanonicalName().equals(annotationName)) {
                processAggregateInfoAnnotations(roundEnv);
            } else if (CommandHandler.class.getCanonicalName().equals(annotationName)) {
                processCommandHandlerAnnotations(roundEnv);
            } else if (EventHandler.class.getCanonicalName().equals(annotationName)) {
                processEventHandlerAnnotations(roundEnv);
            }
        }

        // now that we have all data, we need to match EventHandlers and CommandHandlers to their aggregate
        matchHandlersToAggregates();
        // then based on the CommandHandler.produces and CommandHandler.errors and EventHandler.produces and EventHandler.errors we can create the list of events and the list of commands
        buildEventRelationships();

        // for each aggregate, generate the catalog entry.
        generateCatalogEntries();

        // Mark as processed
        processed = true;
        return processed;
    }

    private void processCommandInfoAnnotations(RoundEnvironment roundEnv) {
        Set<? extends Element> commandElements = roundEnv.getElementsAnnotatedWith(
                processingEnv.getElementUtils().getTypeElement(CommandInfo.class.getCanonicalName())
        );

        for (Element element : commandElements) {
            if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.RECORD) {
                TypeElement typeElement = (TypeElement) element;
                CommandInfo commandInfo = typeElement.getAnnotation(CommandInfo.class);
                commandCache.put(commandInfo, typeElement);

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Found command: " + commandInfo.type() + "-v" + commandInfo.version() + " at " + typeElement.getQualifiedName()
                );
            }
        }
    }

    private void processDomainEventInfoAnnotations(RoundEnvironment roundEnv) {
        Set<? extends Element> eventElements = roundEnv.getElementsAnnotatedWith(
                processingEnv.getElementUtils().getTypeElement(DomainEventInfo.class.getCanonicalName())
        );

        for (Element element : eventElements) {
            if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.RECORD) {
                TypeElement typeElement = (TypeElement) element;
                DomainEventInfo eventInfo = typeElement.getAnnotation(DomainEventInfo.class);
                eventCache.put(eventInfo, typeElement);

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Found event: " + eventInfo.type() + "-v" + eventInfo.version() + " at " + typeElement.getQualifiedName()
                );
            }
        }
    }

    private void processAggregateInfoAnnotations(RoundEnvironment roundEnv) {
        Set<? extends Element> aggregateElements = roundEnv.getElementsAnnotatedWith(
                processingEnv.getElementUtils().getTypeElement(AggregateInfo.class.getCanonicalName())
        );

        for (Element element : aggregateElements) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement typeElement = (TypeElement) element;
                AggregateInfo aggregateInfo = typeElement.getAnnotation(AggregateInfo.class);
                String id = aggregateInfo.value().isEmpty() ? typeElement.getSimpleName().toString() : aggregateInfo.value();
                aggregateCache.put(aggregateInfo, typeElement);

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Found aggregate: " + id + " at " + typeElement.getQualifiedName()
                );
            }
        }
    }

    private void processCommandHandlerAnnotations(RoundEnvironment roundEnv) {
        Set<? extends Element> handlerElements = roundEnv.getElementsAnnotatedWith(
                processingEnv.getElementUtils().getTypeElement(CommandHandler.class.getCanonicalName())
        );

        for (Element element : handlerElements) {
            if (element.getKind() == ElementKind.METHOD) {
                CommandHandler handlerInfo = element.getAnnotation(CommandHandler.class);
                commandHandlerCache.put(handlerInfo, element);
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Found command handler at " + element.getEnclosingElement() + "." + element
                );
            }
        }
    }

    private void processEventHandlerAnnotations(RoundEnvironment roundEnv) {
        Set<? extends Element> handlerElements = roundEnv.getElementsAnnotatedWith(
                processingEnv.getElementUtils().getTypeElement(EventHandler.class.getCanonicalName())
        );

        for (Element element : handlerElements) {
            if (element.getKind() == ElementKind.METHOD) {
                EventHandler handlerInfo = element.getAnnotation(EventHandler.class);
                eventHandlerCache.put(handlerInfo, element);

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Found event handler at " + element.getEnclosingElement() + "." + element
                );
            }
        }
    }

    /**
     * Match command and event handlers to their enclosing aggregates
     */
    private void matchHandlersToAggregates() {
        // Create a map of TypeElement to AggregateInfo for quick lookup
        Map<TypeElement, AggregateInfo> typeToAggregate = new HashMap<>();
        for (Map.Entry<AggregateInfo, TypeElement> entry : aggregateCache.entrySet()) {
            typeToAggregate.put(entry.getValue(), entry.getKey());
        }

        // Match command handlers to aggregates
        for (Map.Entry<CommandHandler, Element> entry : commandHandlerCache.entrySet()) {
            CommandHandler handler = entry.getKey();
            Element methodElement = entry.getValue();
            Element enclosingClass = methodElement.getEnclosingElement();

            if (enclosingClass.getKind() == ElementKind.CLASS) {
                TypeElement enclosingType = (TypeElement) enclosingClass;
                AggregateInfo aggregateInfo = typeToAggregate.get(enclosingType);

                if (aggregateInfo != null) {
                    // Found a match
                    aggregateToCommandHandlers
                            .computeIfAbsent(aggregateInfo, k -> new ArrayList<>())
                            .add(handler);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Matched command handler " + methodElement + " to aggregate " +
                                    enclosingType.getQualifiedName()
                    );
                }
            }
        }

        // Match event handlers to aggregates
        for (Map.Entry<EventHandler, Element> entry : eventHandlerCache.entrySet()) {
            EventHandler handler = entry.getKey();
            Element methodElement = entry.getValue();
            Element enclosingClass = methodElement.getEnclosingElement();

            if (enclosingClass.getKind() == ElementKind.CLASS) {
                TypeElement enclosingType = (TypeElement) enclosingClass;
                AggregateInfo aggregateInfo = typeToAggregate.get(enclosingType);

                if (aggregateInfo != null) {
                    // Found a match
                    aggregateToEventHandlers
                            .computeIfAbsent(aggregateInfo, k -> new ArrayList<>())
                            .add(handler);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Matched event handler " + methodElement + " to aggregate " +
                                    enclosingType.getQualifiedName()
                    );
                }
            }
        }
    }

    /**
     * Process the produces and errors properties from handlers to build event relationships
     */
    private void buildEventRelationships() {

        // Populate lookup maps for commands
        for (Map.Entry<CommandInfo, TypeElement> entry : commandCache.entrySet()) {
            TypeElement typeElement = entry.getValue();
            AnnotationMirror annotationMirror = getAnnotationMirror(typeElement, CommandInfo.class.getCanonicalName());
            if (annotationMirror != null) {
                commandAnnotations.put(typeElement, annotationMirror);
            }
        }

        // Populate lookup maps for events
        for (Map.Entry<DomainEventInfo, TypeElement> entry : eventCache.entrySet()) {
            TypeElement typeElement = entry.getValue();
            AnnotationMirror annotationMirror = getAnnotationMirror(typeElement, DomainEventInfo.class.getCanonicalName());
            if (annotationMirror != null) {
                eventAnnotations.put(typeElement, annotationMirror);
            }
        }

        // Process command handlers
        processCommandHandlers();

        // Process event handlers
        processEventHandlers();

        // Log the relationship details with annotation information
        logAggregateRelationships();
    }

    private void processCommandHandlers() {

        for (Map.Entry<AggregateInfo, List<CommandHandler>> entry : aggregateToCommandHandlers.entrySet()) {
            AggregateInfo aggregateInfo = entry.getKey();
            List<CommandHandler> handlers = entry.getValue();

            Set<TypeElement> producedEvents = aggregateProducedEvents.computeIfAbsent(
                    aggregateInfo, k -> new HashSet<>());
            Set<TypeElement> errorEvents = aggregateErrorEvents.computeIfAbsent(
                    aggregateInfo, k -> new HashSet<>());
            Set<TypeElement> handledCommands = aggregateHandledCommands.computeIfAbsent(
                    aggregateInfo, k -> new HashSet<>());

            for (CommandHandler handler : handlers) {
                // Extract command from method parameter
                Element methodElement = commandHandlerCache.get(handler);
                if (methodElement instanceof ExecutableElement executableElement) {
                    List<? extends VariableElement> parameters = executableElement.getParameters();
                    if (!parameters.isEmpty()) {
                        VariableElement firstParam = parameters.getFirst();
                        TypeMirror commandTypeMirror = firstParam.asType();
                        TypeElement commandTypeElement = getTypeElementFromTypeMirror(commandTypeMirror);
                        if (commandTypeElement != null) {
                            handledCommands.add(commandTypeElement);
                        }
                    }
                }

                // Extract produced events from annotation
                AnnotationMirror handlerMirror = getAnnotationMirror(methodElement, CommandHandler.class.getCanonicalName());
                if (handlerMirror != null) {
                    // Extract produces
                    List<TypeElement> produces = getClassArrayFromAnnotation(handlerMirror, "produces");
                    if (produces != null) {
                        producedEvents.addAll(produces);
                    }

                    // Extract errors
                    List<TypeElement> errors = getClassArrayFromAnnotation(handlerMirror, "errors");
                    if (errors != null) {
                        errorEvents.addAll(errors);
                    }
                }
            }
        }
    }

    private void processEventHandlers() {

        for (Map.Entry<AggregateInfo, List<EventHandler>> entry : aggregateToEventHandlers.entrySet()) {
            AggregateInfo aggregateInfo = entry.getKey();
            List<EventHandler> handlers = entry.getValue();

            Set<TypeElement> producedEvents = aggregateProducedEvents.computeIfAbsent(
                    aggregateInfo, k -> new HashSet<>());
            Set<TypeElement> errorEvents = aggregateErrorEvents.computeIfAbsent(
                    aggregateInfo, k -> new HashSet<>());

            for (EventHandler handler : handlers) {
                Element methodElement = eventHandlerCache.get(handler);

                // Extract event from method parameter
                if (methodElement instanceof ExecutableElement executableElement) {
                    List<? extends VariableElement> parameters = executableElement.getParameters();
                    if (!parameters.isEmpty()) {
                        // Could track consumed events here if needed
                    }
                }

                // Extract produced events and errors from annotation
                AnnotationMirror handlerMirror = getAnnotationMirror(methodElement, EventHandler.class.getCanonicalName());
                if (handlerMirror != null) {
                    // Extract produces
                    List<TypeElement> produces = getClassArrayFromAnnotation(handlerMirror, "produces");
                    if (produces != null) {
                        producedEvents.addAll(produces);
                    }

                    // Extract errors
                    List<TypeElement> errors = getClassArrayFromAnnotation(handlerMirror, "errors");
                    if (errors != null) {
                        errorEvents.addAll(errors);
                    }
                }
            }
        }
    }

    private void logAggregateRelationships() {

        for (AggregateInfo aggregateInfo : aggregateCache.keySet()) {
            TypeElement aggregateType = aggregateCache.get(aggregateInfo);
            String aggregateName = aggregateType.getSimpleName().toString();

            // Log commands with their annotation details
            Set<TypeElement> commands = aggregateHandledCommands.getOrDefault(aggregateInfo, Collections.emptySet());
            if (!commands.isEmpty()) {
                StringBuilder commandsInfo = new StringBuilder();
                for (TypeElement command : commands) {
                    commandsInfo.append(command.getSimpleName());

                    // Add annotation details if available
                    AnnotationMirror cmdAnnotation = commandAnnotations.get(command);
                    if (cmdAnnotation != null) {
                        String type = getAnnotationValue(cmdAnnotation, "type");
                        String version = getAnnotationValue(cmdAnnotation, "version");
                        commandsInfo.append("[type=").append(type)
                                .append(", v=").append(version).append("] ");
                    } else {
                        commandsInfo.append(" ");
                    }
                }

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Aggregate " + aggregateName + " handles commands: " + commandsInfo
                );
            }

        }
    }

    /**
     * Get the AnnotationMirror for a specific annotation on an element
     */
    private AnnotationMirror getAnnotationMirror(Element element, String annotationClassName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String annotationName = mirror.getAnnotationType().toString();
            if (annotationClassName.equals(annotationName)) {
                return mirror;
            }
        }
        return null;
    }

    /**
     * Extract an array of Class values from an annotation and convert to TypeElements
     */
    private List<TypeElement> getClassArrayFromAnnotation(AnnotationMirror annotationMirror, String elementName) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                annotationMirror.getElementValues().entrySet()) {

            if (elementName.equals(entry.getKey().getSimpleName().toString())) {
                AnnotationValue annotationValue = entry.getValue();
                if (annotationValue.getValue() instanceof List<?> valueList) {
                    List<TypeElement> result = new ArrayList<>();
                    for (Object value : valueList) {
                        if (value instanceof AnnotationValue av) {
                            // Class literals are represented as DeclaredType
                            if (av.getValue() instanceof DeclaredType declaredType) {
                                TypeElement typeElement = (TypeElement) declaredType.asElement();
                                result.add(typeElement);
                            }
                        }
                    }
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Extract a simple value from an annotation
     */
    private String getAnnotationValue(AnnotationMirror annotationMirror, String elementName) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                annotationMirror.getElementValues().entrySet()) {

            if (elementName.equals(entry.getKey().getSimpleName().toString())) {
                return entry.getValue().getValue().toString();
            }
        }
        return "";
    }

    /**
     * Helper method to get a TypeElement from a TypeMirror.
     */
    private TypeElement getTypeElementFromTypeMirror(TypeMirror typeMirror) {
        try {
            if (typeMirror instanceof DeclaredType declaredType) {
                Element element = declaredType.asElement();
                if (element instanceof TypeElement typeElement) {
                    return typeElement;
                }
            }
            return null;
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    "Could not get TypeElement for: " + typeMirror + " - " + e.getMessage()
            );
            return null;
        }
    }

    private String generateJsonSchema(TypeElement typeElement, String category, String type, int version) {
        try {
            // Create JSON schema based on class structure
            Map<String, Object> schema = new HashMap<>();
            schema.put("$schema", "http://json-schema.org/draft-07/schema#");
            schema.put("title", type);
            schema.put("type", "object");

            // Generate properties map for the schema
            Map<String, Object> properties = new HashMap<>();
            List<String> requiredProps = new ArrayList<>();

            // Extract fields from the class
            for (Element enclosedElement : typeElement.getEnclosedElements()) {
                if (enclosedElement.getKind() == ElementKind.FIELD) {
                    VariableElement field = (VariableElement) enclosedElement;

                    // Skip static and transient fields
                    Set<Modifier> modifiers = field.getModifiers();
                    if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.TRANSIENT)) {
                        continue;
                    }

                    String fieldName = field.getSimpleName().toString();
                    TypeMirror fieldType = field.asType();

                    Map<String, Object> fieldSchema = mapTypeToJsonSchema(fieldType);

                    // Add description if available from JavaDoc
                    String docComment = processingEnv.getElementUtils().getDocComment(field);
                    if (docComment != null && !docComment.isEmpty()) {
                        fieldSchema.put("description", docComment.trim());
                    }

                    // Check for @NotNull or similar annotations
                    boolean required = hasRequiredAnnotation(field);
                    if (required) {
                        requiredProps.add(fieldName);
                    }

                    // Handle special formats
                    handleSpecialFormats(fieldName, fieldType, fieldSchema);

                    properties.put(fieldName, fieldSchema);
                }
            }

            schema.put("properties", properties);

            // Add required properties if any exist
            if (!requiredProps.isEmpty()) {
                schema.put("required", requiredProps);
            }

            // Disallow additional properties
            schema.put("additionalProperties", false);

            // Convert schema to JSON
            return mapToJson(schema);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Error generating JSON schema for " + typeElement + ": " + e.getMessage(),
                    typeElement);
            throw e;
        }
    }

    private String extractDomainName(String packageName) {
        // Extract domain name from package structure
        // Example: org.elasticsoftware.akces.accounts -> Accounts
        String[] parts = packageName.split("\\.");
        for (String part : parts) {
            // Try to find a domain-like name (usually capitalized)
            if (Character.isUpperCase(part.charAt(0))) {
                return part;
            }
        }
        // Default if no suitable part found
        return "Core";
    }

    private void handleSpecialFormats(String fieldName, TypeMirror fieldType, Map<String, Object> fieldSchema) {
        // Set formats based on field names or types
        String typeName = fieldType.toString();

        // UUID fields
        if (fieldName.toLowerCase().contains("uuid") ||
                fieldName.toLowerCase().endsWith("id") ||
                typeName.contains("UUID")) {
            if ("string".equals(fieldSchema.get("type"))) {
                fieldSchema.put("format", "uuid");
            }
        }

        // Email fields
        if (fieldName.toLowerCase().contains("email")) {
            if ("string".equals(fieldSchema.get("type"))) {
                fieldSchema.put("format", "email");
            }
        }

        // Date/time fields
        if (fieldName.toLowerCase().contains("date") ||
                fieldName.toLowerCase().contains("time") ||
                typeName.contains("Date") ||
                typeName.contains("Time")) {
            if ("string".equals(fieldSchema.get("type"))) {
                if (typeName.contains("LocalDate")) {
                    fieldSchema.put("format", "date");
                } else if (typeName.contains("LocalTime")) {
                    fieldSchema.put("format", "time");
                } else {
                    fieldSchema.put("format", "date-time");
                }
            }
        }
    }

    private Map<String, Object> mapTypeToJsonSchema(TypeMirror typeMirror) {
        Map<String, Object> schema = new HashMap<>();

        // Handle primitive types
        if (typeMirror.getKind().isPrimitive()) {
            switch (typeMirror.getKind()) {
                case BOOLEAN:
                    schema.put("type", "boolean");
                    break;
                case INT:
                case LONG:
                case SHORT:
                case BYTE:
                    schema.put("type", "integer");
                    break;
                case FLOAT:
                case DOUBLE:
                    schema.put("type", "number");
                    break;
                default:
                    schema.put("type", "string");
                    break;
            }
            return schema;
        }

        // Handle common Java types
        String typeName = typeMirror.toString();
        if (typeName.equals("java.lang.String")) {
            schema.put("type", "string");
        } else if (typeName.equals("java.lang.Boolean")) {
            schema.put("type", "boolean");
        } else if (typeName.equals("java.lang.Integer") ||
                typeName.equals("java.lang.Long") ||
                typeName.equals("java.lang.Short") ||
                typeName.equals("java.lang.Byte")) {
            schema.put("type", "integer");
        } else if (typeName.equals("java.lang.Float") ||
                typeName.equals("java.lang.Double")) {
            schema.put("type", "number");
        } else if (typeName.equals("java.util.UUID")) {
            schema.put("type", "string");
            schema.put("format", "uuid");
        } else if (typeName.startsWith("java.time.")) {
            schema.put("type", "string");
            if (typeName.contains("LocalDate")) {
                schema.put("format", "date");
            } else if (typeName.contains("LocalTime")) {
                schema.put("format", "time");
            } else {
                schema.put("format", "date-time");
            }
        } else if (typeName.startsWith("java.util.List") ||
                typeName.startsWith("java.util.ArrayList") ||
                typeName.startsWith("java.util.Collection")) {
            schema.put("type", "array");

            // Try to determine the item type for generics
            if (typeMirror instanceof DeclaredType declaredType && !declaredType.getTypeArguments().isEmpty()) {
                TypeMirror itemType = declaredType.getTypeArguments().getFirst();
                schema.put("items", mapTypeToJsonSchema(itemType));
            } else {
                // Default to any type if we can't determine
                Map<String, Object> anyType = new HashMap<>();
                anyType.put("type", "object");
                schema.put("items", anyType);
            }
        } else if (typeName.startsWith("java.util.Map") ||
                typeName.startsWith("java.util.HashMap")) {
            schema.put("type", "object");
            schema.put("additionalProperties", true);
        } else if (typeName.startsWith("java.util.Set") ||
                typeName.startsWith("java.util.HashSet")) {
            schema.put("type", "array");
            schema.put("uniqueItems", true);

            // Try to determine item type
            if (typeMirror instanceof DeclaredType declaredType && !declaredType.getTypeArguments().isEmpty()) {
                TypeMirror itemType = declaredType.getTypeArguments().getFirst();
                schema.put("items", mapTypeToJsonSchema(itemType));
            } else {
                Map<String, Object> anyType = new HashMap<>();
                anyType.put("type", "object");
                schema.put("items", anyType);
            }
        } else if (typeMirror instanceof DeclaredType) {
            // For custom objects, refer to them as objects
            schema.put("type", "object");
            // You could recursively generate the schema for this type too
        } else {
            // Default to string for unknown types
            schema.put("type", "string");
        }

        return schema;
    }

    private boolean hasRequiredAnnotation(Element element) {
        // Check for common validation annotations
        List<String> requiredAnnotations = List.of(
                "jakarta.validation.constraints.NotNull",
                "jakarta.validation.constraints.NotBlank",
                "jakarta.validation.constraints.NotEmpty",
                "org.springframework.lang.NonNull",
                "lombok.NonNull"
        );

        for (String annotationName : requiredAnnotations) {
            if (element.getAnnotationMirrors().stream()
                    .anyMatch(am -> am.getAnnotationType().toString().equals(annotationName))) {
                return true;
            }
        }

        return false;
    }

    private String mapToJson(Map<String, Object> map) {
        // Recursive pretty printing of JSON
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            json.append("  \"").append(entry.getKey()).append("\": ");
            appendValue(json, entry.getValue(), 2);

            if (it.hasNext()) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("}");
        return json.toString();
    }

    private void appendValue(StringBuilder json, Object value, int indent) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof String) {
            json.append("\"").append(escapeJsonString(value.toString())).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                json.append("[]");
                return;
            }

            json.append("[\n");
            String indentStr = " ".repeat(indent);

            for (int i = 0; i < list.size(); i++) {
                json.append(indentStr).append("  ");
                appendValue(json, list.get(i), indent + 2);

                if (i < list.size() - 1) {
                    json.append(",");
                }
                json.append("\n");
            }

            json.append(indentStr).append("]");
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) {
                json.append("{}");
                return;
            }

            json.append("{\n");

            String indentStr = " ".repeat(indent);
            Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<String, Object> entry = it.next();
                json.append(indentStr).append("  \"").append(entry.getKey()).append("\": ");
                appendValue(json, entry.getValue(), indent + 2);

                if (it.hasNext()) {
                    json.append(",");
                }
                json.append("\n");
            }

            json.append(indentStr).append("}");
        } else {
            json.append("\"").append(escapeJsonString(value.toString())).append("\"");
        }
    }

    /**
     * Escapes special characters in a JSON string.
     */
    private String escapeJsonString(String input) {
        if (input == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    // Handle control characters
                    if (ch < ' ') {
                        String hex = Integer.toHexString(ch);
                        escaped.append("\\u");
                        // Pad with zeros to make it 4 digits
                        escaped.append("0".repeat(4 - hex.length()));
                        escaped.append(hex);
                    } else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
    }


    private void generateCatalogEntries() {
        // Create a map of aggregates to their domain names
        Map<AggregateInfo, String> aggregateDomains = new HashMap<>();

        // For each aggregate, find its domain and create service documentation
        for (Map.Entry<AggregateInfo, TypeElement> entry : aggregateCache.entrySet()) {
            AggregateInfo aggregateInfo = entry.getKey();
            TypeElement aggregateType = entry.getValue();

            // Extract domain from package name
            String packageName = processingEnv.getElementUtils().getPackageOf(aggregateType).getQualifiedName().toString();
            String domainName = extractDomainName(packageName);
            aggregateDomains.put(aggregateInfo, domainName);

            String aggregateName = aggregateInfo.value().isEmpty() ?
                    aggregateType.getSimpleName().toString() : aggregateInfo.value();

            // Generate service documentation
            String servicePath = "eventcatalog/domains/" + domainName + "/services/" + aggregateName + "Aggregate";
            String serviceDoc = ServiceTemplateGenerator.generate(new ServiceTemplateGenerator.ServiceMetadata(
                    aggregateName,
                    "1.0.0", // TODO: take this version from the aggregate`state info
                    aggregateName,
                    "",
                    List.of("framework-developers"),
                    // iterator over all commands
                    aggregateHandledCommands.get(aggregateInfo).stream()
                            .map(typeElement -> commandAnnotations.get(typeElement))
                            .map(mirror -> new ServiceTemplateGenerator.Command(
                                    getAnnotationValue(mirror, "type"),
                                    getAnnotationValue(mirror, "version") + ".0.0"))
                            .toList(),
                    // iterator over all events and error events
                    Stream.concat(
                                    aggregateProducedEvents.getOrDefault(aggregateInfo, Collections.emptySet()).stream()
                                            .map(typeElement -> eventAnnotations.get(typeElement))
                                            .filter(Objects::nonNull),
                                    aggregateErrorEvents.getOrDefault(aggregateInfo, Collections.emptySet()).stream()
                                            .map(typeElement -> eventAnnotations.get(typeElement))
                                            .filter(Objects::nonNull)
                            )
                            .map(mirror -> new ServiceTemplateGenerator.Event(
                                    getAnnotationValue(mirror, "type"),
                                    getAnnotationValue(mirror, "version") + ".0.0"))
                            .toList(),
                    "Java",
                    "https://github.com/elasticsoftwarefoundation/akces-framework"));

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Generated service documentation for " + aggregateName
            );

            writeToEventCatalog(servicePath, "index.mdx", serviceDoc);
        }

        // Generate command documentation and schemas
        for (Map.Entry<CommandInfo, TypeElement> entry : commandCache.entrySet()) {
            CommandInfo commandInfo = entry.getKey();
            TypeElement commandType = entry.getValue();

            // Find which aggregate this command belongs to
            for (Map.Entry<AggregateInfo, Set<TypeElement>> aggEntry : aggregateHandledCommands.entrySet()) {
                if (aggEntry.getValue().contains(commandType)) {
                    AggregateInfo aggregateInfo = aggEntry.getKey();
                    String domainName = aggregateDomains.get(aggregateInfo);
                    String aggregateName = aggregateInfo.value();

                    if (domainName == null || aggregateName.isEmpty()) {
                        continue;
                    }

                    // Generate command documentation
                    String commandName = commandInfo.type();
                    String commandPath = "eventcatalog/domains/" + domainName + "/services/" +
                            aggregateName + "Aggregate/commands/" + commandName;

                    String commandDoc = DomainEventTemplateGenerator.generate(new DomainEventTemplateGenerator.EventMetadata(
                                    commandInfo.type(),
                                    commandInfo.type(),
                                    commandInfo.version() + ".0.0",
                                    "",
                                    List.of("framework-developers"),
                                    "schema.json",
                                    "Java",
                                    "https://github.com/elasticsoftwarefoundation/akces-framework" // TODO: generate the path
                            )
                    );

                    writeToEventCatalog(commandPath, "index.mdx", commandDoc);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Generated command documentation for " + commandName
                    );

                    // Generate command schema
                    String schema = generateJsonSchema(commandType, "command", commandInfo.type(), commandInfo.version());

                    writeToEventCatalog(commandPath, "schema.json", schema);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Generated schema for command " + commandName
                    );

                    break;
                }
            }
        }

        // Generate event documentation and schemas
        for (Map.Entry<DomainEventInfo, TypeElement> entry : eventCache.entrySet()) {
            DomainEventInfo eventInfo = entry.getKey();
            TypeElement eventType = entry.getValue();

            // Find which aggregate this event belongs to
            for (Map.Entry<AggregateInfo, Set<TypeElement>> aggEntry : aggregateProducedEvents.entrySet()) {
                if (aggEntry.getValue().contains(eventType)) {
                    AggregateInfo aggregateInfo = aggEntry.getKey();
                    String domainName = aggregateDomains.get(aggregateInfo);
                    String aggregateName = aggregateInfo.value();

                    if (domainName == null || aggregateName.isEmpty()) {
                        continue;
                    }

                    // Generate event documentation
                    String eventName = eventInfo.type();
                    String versionSuffix = eventInfo.version() > 1 ? "-v" + eventInfo.version() : "";
                    String eventPath = "eventcatalog/domains/" + domainName + "/services/" +
                            aggregateName + "Aggregate/events/" + eventName;

                    String eventDoc = DomainEventTemplateGenerator.generate(new DomainEventTemplateGenerator.EventMetadata(
                            eventInfo.type(),
                            eventInfo.type(),
                            eventInfo.version() + ".0.0",
                            "",
                            List.of("framework-developers"),
                            "schema.json",
                            "Java",
                            "https://github.com/elasticsoftwarefoundation/akces-framework" // TODO: generate the path
                    ));

                    writeToEventCatalog(eventPath, "index.mdx", eventDoc);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Generated event documentation for " + eventName + versionSuffix
                    );

                    // Generate event schema
                    String schema = generateJsonSchema(eventType, "event", eventInfo.type(), eventInfo.version());

                    writeToEventCatalog(eventPath, "schema.json", schema);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Generated schema for event " + eventName + versionSuffix
                    );

                    break;
                }
            }
        }
    }

    /**
     * Write content to a file in the event catalog structure
     *
     * @param path     The relative path within the eventcatalog folder
     * @param fileName The name of the file to create
     * @param content  The content to write to the file
     */
    private void writeToEventCatalog(String path, String fileName, String content) {
        try {
            // Create the full path
            FileObject resourceFile = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    path + "/" + fileName
            );

            try (Writer writer = resourceFile.openWriter()) {
                writer.write(content);
            }

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Successfully wrote file: " + path + "/" + fileName
            );
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Error writing file " + path + "/" + fileName + ": " + e.getMessage()
            );
        }
    }

}
