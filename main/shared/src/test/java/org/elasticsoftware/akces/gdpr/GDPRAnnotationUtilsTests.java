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

package org.elasticsoftware.akces.gdpr;

import org.elasticsoftware.akces.annotations.PIIData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GDPRAnnotationUtilsTests {

    @Test
    public void testHasPIIDataAnnotationWithAnnotatedField() {
        assertTrue(GDPRAnnotationUtils.hasPIIDataAnnotation(ClassWithPIIField.class));
    }

    @Test
    public void testHasPIIDataAnnotationWithAnnotatedMethod() {
        assertTrue(GDPRAnnotationUtils.hasPIIDataAnnotation(ClassWithPIIMethod.class));
    }

    @Test
    public void testHasPIIDataAnnotationWithAnnotatedConstructorParameter() {
        assertTrue(GDPRAnnotationUtils.hasPIIDataAnnotation(ClassWithPIIConstructorParam.class));
    }

    @Test
    public void testHasPIIDataAnnotationWithNoPII() {
        assertFalse(GDPRAnnotationUtils.hasPIIDataAnnotation(ClassWithoutPII.class));
    }

    @Test
    public void testHasPIIDataAnnotationWithNestedPII() {
        assertTrue(GDPRAnnotationUtils.hasPIIDataAnnotation(ClassWithNestedPII.class));
    }

    @Test
    public void testHasPIIDataAnnotationWithSimpleTypes() {
        assertFalse(GDPRAnnotationUtils.hasPIIDataAnnotation(ClassWithOnlySimpleTypes.class));
    }

    // Test classes
    static class ClassWithPIIField {
        @PIIData
        private String name;
    }

    static class ClassWithPIIMethod {
        @PIIData
        public String getName() {
            return "test";
        }
    }

    static class ClassWithPIIConstructorParam {
        private String email;
        
        public ClassWithPIIConstructorParam(@PIIData String email) {
            this.email = email;
        }
    }

    static class ClassWithoutPII {
        private String id;
        private int count;
    }

    static class ClassWithNestedPII {
        private ClassWithPIIField nested;
    }

    static class ClassWithOnlySimpleTypes {
        private String id;
        private int number;
        private boolean flag;
    }
}
