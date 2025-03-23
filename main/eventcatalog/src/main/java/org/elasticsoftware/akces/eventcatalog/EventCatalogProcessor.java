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

import com.google.auto.service.AutoService;
import org.elasticsoftware.akces.annotations.*;

import javax.annotation.processing.*;
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

import static java.util.Objects.requireNonNull;

@SupportedAnnotationTypes({
        "org.elasticsoftware.akces.annotations.CommandInfo",
        "org.elasticsoftware.akces.annotations.DomainEventInfo",
        "org.elasticsoftware.akces.annotations.AggregateInfo",
        "org.elasticsoftware.akces.annotations.CommandHandler",
        "org.elasticsoftware.akces.annotations.EventHandler"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@AutoService(Processor.class)
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
    Map<AggregateInfo, Set<TypeElement>> aggregateHandledEvents = new HashMap<>();
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
        // get the repository base url and owners from the configuration
        String repoBaseUrl = processingEnv.getOptions().getOrDefault(
                "eventcatalog.repositoryBaseUrl",
                "https://github.com/elasticsoftwarefoundation/akces-framework/"
        );
        // Ensure trailing slash
        if (!repoBaseUrl.endsWith("/")) {
            repoBaseUrl += "/";
        }
        List<String> owners = Arrays.asList(processingEnv.getOptions().getOrDefault(
                "eventcatalog.owners",
                "framework-developers"
        ).split("\\s*,\\s*"));

        String schemaDomain = processingEnv.getOptions().getOrDefault(
                "eventcatalog.schemaDomain",
                "akces-framework"
        );

        // for each aggregate, generate the catalog entry.
        generateCatalogEntries(repoBaseUrl, owners, schemaDomain);

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
            // handled events
            Set<TypeElement> handledEvents = aggregateHandledEvents.computeIfAbsent(
                    aggregateInfo, k -> new HashSet<>());

            for (EventHandler handler : handlers) {
                Element methodElement = eventHandlerCache.get(handler);

                // Extract event from method parameter
                if (methodElement instanceof ExecutableElement executableElement) {
                    List<? extends VariableElement> parameters = executableElement.getParameters();
                    if (!parameters.isEmpty()) {
                        VariableElement firstParam = parameters.getFirst();
                        TypeMirror eventTypeMirror = firstParam.asType();
                        TypeElement eventTypeElement = getTypeElementFromTypeMirror(eventTypeMirror);
                        if (eventTypeElement != null) {
                            handledEvents.add(eventTypeElement);
                        }
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
     * Extract a single Class value from an annotation and convert it to TypeElement
     *
     * @param annotationMirror the annotation mirror to extract from
     * @param elementName the name of the annotation attribute
     * @return the TypeElement representing the class, or null if not found
     */
    private TypeElement getClassFromAnnotation(AnnotationMirror annotationMirror, String elementName) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                annotationMirror.getElementValues().entrySet()) {

            if (elementName.equals(entry.getKey().getSimpleName().toString())) {
                AnnotationValue annotationValue = entry.getValue();
                Object value = annotationValue.getValue();

                // Single class values are represented directly as DeclaredType
                if (value instanceof DeclaredType declaredType) {
                    return (TypeElement) declaredType.asElement();
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


    private void generateCatalogEntries(String repositoryBaseUrl, List<String> owners, String schemaDomain) {
        // For each aggregate, find its domain and create service documentation
        for (Map.Entry<AggregateInfo, TypeElement> entry : aggregateCache.entrySet()) {
            AggregateInfo aggregateInfo = entry.getKey();
            TypeElement aggregateType = entry.getValue();

            String aggregateName = aggregateInfo.value().isEmpty() ?
                    aggregateType.getSimpleName().toString() : aggregateInfo.value();

            // get the AggregateStateInfo annotation from the aggregateInfo.stateClass
            AnnotationMirror aggregateInfoMirror = getAnnotationMirror(aggregateType, AggregateInfo.class.getCanonicalName());
            // get the stateClass type from the mirror
            TypeElement stateClass = getClassFromAnnotation(requireNonNull(aggregateInfoMirror), "stateClass");
            AggregateStateInfo stateInfo = requireNonNull(stateClass).getAnnotation(AggregateStateInfo.class);

            // Generate service documentation
            String servicePath = "META-INF/eventcatalog/services/" + aggregateName;
            String serviceDoc = ServiceTemplateGenerator.generate(new ServiceTemplateGenerator.ServiceMetadata(
                    aggregateName,
                    stateInfo.version()+".0.0",
                    aggregateName,
                    aggregateInfo.description().isBlank() ? aggregateName + " Aggregate" : aggregateInfo.description(),
                    owners,
                    // iterate over all handle commands and handled events
                    Stream.concat(
                                    aggregateHandledCommands.getOrDefault(aggregateInfo, Collections.emptySet()).stream()
                                            .map(typeElement -> typeElement.getAnnotation(CommandInfo.class))
                                            .filter(Objects::nonNull)
                                            .map(annotation -> new ServiceTemplateGenerator.Message(
                                                    annotation.type(),
                                                    annotation.version() + ".0.0")),
                                    aggregateHandledEvents.getOrDefault(aggregateInfo, Collections.emptySet()).stream()
                                            .map(typeElement -> typeElement.getAnnotation(DomainEventInfo.class))
                                            .filter(Objects::nonNull)
                                            .map(annotation -> new ServiceTemplateGenerator.Message(
                                                    annotation.type(),
                                                    annotation.version() + ".0.0"))
                            ).toList(),
                    // iterator over all events and error events
                    Stream.concat(
                                    aggregateProducedEvents.getOrDefault(aggregateInfo, Collections.emptySet()).stream()
                                            .map(typeElement -> typeElement.getAnnotation(DomainEventInfo.class))
                                            .filter(Objects::nonNull)
                                            .map(annotation -> new ServiceTemplateGenerator.Message(
                                                    annotation.type(),
                                                    annotation.version() + ".0.0")),
                                    aggregateErrorEvents.getOrDefault(aggregateInfo, Collections.emptySet()).stream()
                                            .map(typeElement -> typeElement.getAnnotation(DomainEventInfo.class))
                                            .filter(Objects::nonNull)
                                            .map(annotation -> new ServiceTemplateGenerator.Message(
                                                    annotation.type(),
                                                    annotation.version() + ".0.0"))
                            ).toList(),
                    "Java",
                    repositoryBaseUrl + aggregateType.getQualifiedName().toString().replace('.', '/') + ".java"));

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
                    String aggregateName = aggregateInfo.value();

                    // Generate command documentation
                    String commandName = commandInfo.type();
                    String commandPath = "META-INF/eventcatalog/services/" +
                            aggregateName + "/commands/" + commandName;

                    String commandDoc = MessageTemplateGenerator.generate(new MessageTemplateGenerator.EventMetadata(
                                    commandInfo.type(),
                                    commandInfo.type(),
                                    commandInfo.version() + ".0.0",
                                    commandInfo.description().isBlank() ? commandInfo.type() + " Command" : commandInfo.description(),
                                    owners,
                                    "Java",
                                    repositoryBaseUrl + commandType.getQualifiedName().toString().replace('.', '/') + ".java"
                            )
                    );

                    writeToEventCatalog(commandPath, "index.mdx", commandDoc);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Generated command documentation for " + commandName
                    );

                    // Generate command schema
                    String schema = JsonSchemaGenerator.generate(processingEnv, commandType, "akces-command:" + schemaDomain , commandInfo.type(), commandInfo.version());

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
                    String aggregateName = aggregateInfo.value();

                    // Generate event documentation
                    String eventName = eventInfo.type();
                    String versionSuffix = eventInfo.version() > 1 ? "-v" + eventInfo.version() : "";
                    String eventPath = "META-INF/eventcatalog/services/" +
                            aggregateName + "/events/" + eventName;

                    String eventDoc = MessageTemplateGenerator.generate(new MessageTemplateGenerator.EventMetadata(
                            eventInfo.type(),
                            eventInfo.type(),
                            eventInfo.version() + ".0.0",
                            eventInfo.description().isBlank() ? eventInfo.type() + " DomainEvent" : eventInfo.description(),
                            owners,
                            "Java",
                            repositoryBaseUrl + eventType.getQualifiedName().toString().replace('.', '/') + ".java"
                    ));

                    writeToEventCatalog(eventPath, "index.mdx", eventDoc);

                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.NOTE,
                            "Generated event documentation for " + eventName + versionSuffix
                    );

                    // Generate event schema
                    String schema = JsonSchemaGenerator.generate(processingEnv, eventType,  "akces-domainevent:" + schemaDomain, eventInfo.type(), eventInfo.version());

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
