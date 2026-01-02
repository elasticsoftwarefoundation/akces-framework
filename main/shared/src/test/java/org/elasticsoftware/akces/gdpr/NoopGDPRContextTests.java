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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NoopGDPRContextTests {

    @Test
    public void testNoopGDPRContextCreation() {
        NoopGDPRContext context = new NoopGDPRContext("test-aggregate-id");
        
        assertNotNull(context);
        assertEquals("test-aggregate-id", context.aggregateId());
    }

    @Test
    public void testEncryptReturnsOriginalData() {
        NoopGDPRContext context = new NoopGDPRContext("test-id");
        
        String data = "sensitive data";
        String encrypted = context.encrypt(data);
        
        assertEquals(data, encrypted);
        assertSame(data, encrypted);  // Should be the same object
    }

    @Test
    public void testEncryptWithNull() {
        NoopGDPRContext context = new NoopGDPRContext("test-id");
        
        assertNull(context.encrypt(null));
    }

    @Test
    public void testDecryptReturnsOriginalData() {
        NoopGDPRContext context = new NoopGDPRContext("test-id");
        
        String encryptedData = "encrypted-data";
        String decrypted = context.decrypt(encryptedData);
        
        assertEquals(encryptedData, decrypted);
        assertSame(encryptedData, decrypted);  // Should be the same object
    }

    @Test
    public void testDecryptWithNull() {
        NoopGDPRContext context = new NoopGDPRContext("test-id");
        
        assertNull(context.decrypt(null));
    }

    @Test
    public void testEncryptDecryptRoundTrip() {
        NoopGDPRContext context = new NoopGDPRContext("test-id");
        
        String original = "John Doe";
        String encrypted = context.encrypt(original);
        String decrypted = context.decrypt(encrypted);
        
        assertEquals(original, encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    public void testAggregateIdAccess() {
        String aggregateId = "user-12345";
        NoopGDPRContext context = new NoopGDPRContext(aggregateId);
        
        assertEquals(aggregateId, context.aggregateId());
    }
}
