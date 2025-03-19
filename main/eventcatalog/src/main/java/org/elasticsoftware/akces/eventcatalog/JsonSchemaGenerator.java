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

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

public class JsonSchemaGenerator {
    private JsonSchemaGenerator() {
    }

    public static String generate(ProcessingEnvironment processingEnv,
                                  TypeElement typeElement,
                                  String category,
                                  String type,
                                  int version) {
        try {
            // Create JSON schema based on class structure
            Map<String, Object> schema = new HashMap<>();
            schema.put("$schema", "http://json-schema.org/draft-07/schema#");
            schema.put("id", "urn:" + category + ":" + type + ":" + version);
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

    private static void handleSpecialFormats(String fieldName, TypeMirror fieldType, Map<String, Object> fieldSchema) {
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

    private static Map<String, Object> mapTypeToJsonSchema(TypeMirror typeMirror) {
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

    private static boolean hasRequiredAnnotation(Element element) {
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

    private static String mapToJson(Map<String, Object> map) {
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

    private static void appendValue(StringBuilder json, Object value, int indent) {
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
    private static String escapeJsonString(String input) {
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
}
