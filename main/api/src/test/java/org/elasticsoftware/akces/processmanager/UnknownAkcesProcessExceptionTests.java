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

package org.elasticsoftware.akces.processmanager;

import org.elasticsoftware.akces.AkcesException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UnknownAkcesProcessExceptionTests {

    @Test
    public void testExceptionCreation() {
        UnknownAkcesProcessException exception = new UnknownAkcesProcessException(
            "OrderProcessManager",
            "order-123",
            "process-456"
        );
        
        assertEquals("OrderProcessManager", exception.getAggregateName());
        assertEquals("order-123", exception.getAggregateId());
        assertEquals("process-456", exception.getProcessId());
    }

    @Test
    public void testExceptionIsAkcesException() {
        UnknownAkcesProcessException exception = new UnknownAkcesProcessException(
            "PaymentProcess",
            "payment-001",
            "proc-789"
        );
        
        assertInstanceOf(AkcesException.class, exception);
        assertInstanceOf(RuntimeException.class, exception);
    }

    @Test
    public void testExceptionCanBeThrown() {
        assertThrows(UnknownAkcesProcessException.class, () -> {
            throw new UnknownAkcesProcessException("TestProcess", "test-id", "unknown-process");
        });
    }

    @Test
    public void testExceptionWithNullProcessId() {
        UnknownAkcesProcessException exception = new UnknownAkcesProcessException(
            "SomeProcess",
            "some-id",
            null
        );
        
        assertNull(exception.getProcessId());
    }
}
