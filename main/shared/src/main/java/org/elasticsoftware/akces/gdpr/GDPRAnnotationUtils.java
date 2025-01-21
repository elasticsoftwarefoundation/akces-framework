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

package org.elasticsoftware.akces.gdpr;

import org.elasticsoftware.akces.annotations.PIIData;
import org.springframework.beans.BeanUtils;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.Arrays.stream;

public final class GDPRAnnotationUtils {
    private GDPRAnnotationUtils() {
    }

    public static Boolean hasPIIDataAnnotation(Class<?> type) {
        return hasAnnotation(type, PIIData.class);
    }

    public static Boolean hasAnnotation(Class<?> type, final Class<? extends Annotation> annotation) {
        return hasAnnotationInternal(type, annotation, new HashSet<>());
    }

    private static Boolean hasAnnotationInternal(Class<?> type, final Class<? extends Annotation> annotation, Set<Class<?>> visitedClasses) {
        visitedClasses.add(type);
        if (methodsHaveAnnotation(type, annotation, visitedClasses)) {
            return TRUE;
        }
        if (constructorParametersHaveAnnotation(type, annotation, visitedClasses)) {
            return TRUE;
        }
        if (fieldsHaveAnnotation(type, annotation, visitedClasses)) {
            return TRUE;
        }

        return FALSE;

    }

    private static boolean methodsHaveAnnotation(Class<?> type, Class<? extends Annotation> annotation, Set<Class<?>> visitedClasses) {
        Class<?> klass = type;
        while (klass != Object.class && klass != null) {
            if (Stream.of(klass.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(annotation) ||
                (!BeanUtils.isSimpleValueType(method.getReturnType()) &&
                !visitedClasses.contains(method.getReturnType()) &&
                hasAnnotationInternal(method.getReturnType(), annotation, visitedClasses)))) {
                return true;
            }
            if (klass.isArray()) {
                klass = klass.getComponentType();
            } else {
                klass = klass.getSuperclass();
            }
        }
        return false;
    }

    private static boolean constructorParametersHaveAnnotation(Class<?> type, Class<? extends Annotation> annotation, Set<Class<?>> visitedClasses) {
        return stream(type.getConstructors())
                .flatMap(constructor -> stream(constructor.getParameters()))
            .anyMatch(parameter -> parameter.isAnnotationPresent(annotation) ||
                (!BeanUtils.isSimpleValueType(parameter.getType()) &&
                !visitedClasses.contains(parameter.getType()) &&
                hasAnnotationInternal(parameter.getType(), annotation, visitedClasses)));
    }

    private static boolean fieldsHaveAnnotation(Class<?> type, Class<? extends Annotation> annotation, Set<Class<?>> visitedClasses) {
        return Arrays.stream(type.getDeclaredFields())
            .anyMatch(field -> field.isAnnotationPresent(annotation) ||
                (!BeanUtils.isSimpleValueType(field.getType()) &&
                !visitedClasses.contains(field.getType()) &&
                hasAnnotationInternal(field.getType(), annotation, visitedClasses)));
    }
}