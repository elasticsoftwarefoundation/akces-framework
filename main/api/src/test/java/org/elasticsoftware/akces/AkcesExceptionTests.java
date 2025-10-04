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

package org.elasticsoftware.akces;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AkcesExceptionTests {

    @Test
    public void testAkcesExceptionCreation() {
        TestAkcesException exception = new TestAkcesException("Wallet", "wallet-123");
        
        assertEquals("Wallet", exception.getAggregateName());
        assertEquals("wallet-123", exception.getAggregateId());
    }

    @Test
    public void testAkcesExceptionWithNullValues() {
        TestAkcesException exception = new TestAkcesException(null, null);
        
        assertNull(exception.getAggregateName());
        assertNull(exception.getAggregateId());
    }

    @Test
    public void testAkcesExceptionIsRuntimeException() {
        TestAkcesException exception = new TestAkcesException("Account", "account-456");
        
        assertInstanceOf(RuntimeException.class, exception);
    }

    @Test
    public void testAkcesExceptionCanBeThrown() {
        assertThrows(TestAkcesException.class, () -> {
            throw new TestAkcesException("Order", "order-789");
        });
    }

    @Test
    public void testAkcesExceptionPreservesAggregateInfo() {
        try {
            throw new TestAkcesException("Payment", "payment-001");
        } catch (AkcesException e) {
            assertEquals("Payment", e.getAggregateName());
            assertEquals("payment-001", e.getAggregateId());
        }
    }

    static class TestAkcesException extends AkcesException {
        public TestAkcesException(String aggregateName, String aggregateId) {
            super(aggregateName, aggregateId);
        }
    }
}
