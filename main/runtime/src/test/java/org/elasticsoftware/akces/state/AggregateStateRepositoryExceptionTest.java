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

package org.elasticsoftware.akces.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AggregateStateRepositoryExceptionTest {

    @Test
    void testExceptionCreation() {
        Throwable cause = new RuntimeException("Root cause");
        AggregateStateRepositoryException exception = 
            new AggregateStateRepositoryException("Test message", cause);
        
        assertEquals("Test message", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void testExceptionWithNullCause() {
        AggregateStateRepositoryException exception = 
            new AggregateStateRepositoryException("Test message", null);
        
        assertEquals("Test message", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void testExceptionIsRuntimeException() {
        AggregateStateRepositoryException exception = 
            new AggregateStateRepositoryException("Test", new Exception());
        
        assertTrue(exception instanceof RuntimeException);
    }
}
