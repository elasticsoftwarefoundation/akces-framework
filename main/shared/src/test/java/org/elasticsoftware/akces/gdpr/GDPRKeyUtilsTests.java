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

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

public class GDPRKeyUtilsTests {

    @Test
    public void testCreateKey() {
        SecretKeySpec key1 = GDPRKeyUtils.createKey();
        SecretKeySpec key2 = GDPRKeyUtils.createKey();
        
        assertNotNull(key1);
        assertNotNull(key2);
        assertEquals("AES", key1.getAlgorithm());
        assertEquals(32, key1.getEncoded().length);
        
        // Keys should be different (random)
        assertNotEquals(key1, key2);
        assertFalse(java.util.Arrays.equals(key1.getEncoded(), key2.getEncoded()));
    }

    @Test
    public void testSecureRandom() {
        SecureRandom random = GDPRKeyUtils.secureRandom();
        
        assertNotNull(random);
        
        // Verify it generates different values
        byte[] bytes1 = new byte[16];
        byte[] bytes2 = new byte[16];
        random.nextBytes(bytes1);
        random.nextBytes(bytes2);
        
        assertFalse(java.util.Arrays.equals(bytes1, bytes2));
    }

    @Test
    public void testIsUUIDWithValidUUID() {
        assertTrue(GDPRKeyUtils.isUUID("ef234add-e0df-4769-b5f4-612a3207bad3"));
        assertTrue(GDPRKeyUtils.isUUID("00000000-0000-0000-0000-000000000000"));
        assertTrue(GDPRKeyUtils.isUUID("123e4567-e89b-12d3-a456-426614174000"));
    }

    @Test
    public void testIsUUIDWithUpperCase() {
        assertTrue(GDPRKeyUtils.isUUID("EF234ADD-E0DF-4769-B5F4-612A3207BAD3"));
        assertTrue(GDPRKeyUtils.isUUID("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"));
    }

    @Test
    public void testIsUUIDWithMixedCase() {
        assertTrue(GDPRKeyUtils.isUUID("EF234add-e0dF-4769-B5f4-612A3207bad3"));
    }

    @Test
    public void testIsUUIDWithInvalidFormats() {
        assertFalse(GDPRKeyUtils.isUUID("not-a-uuid"));
        assertFalse(GDPRKeyUtils.isUUID(""));
        assertFalse(GDPRKeyUtils.isUUID("12345678-1234-1234-1234-123456789"));  // Too short
        assertFalse(GDPRKeyUtils.isUUID("12345678-1234-1234-1234-1234567890123"));  // Too long
        assertFalse(GDPRKeyUtils.isUUID("12345678123412341234123456789012"));  // No dashes
        assertFalse(GDPRKeyUtils.isUUID("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"));  // Invalid hex
    }

    @Test
    public void testIsUUIDWithNull() {
        assertThrows(NullPointerException.class, () -> GDPRKeyUtils.isUUID(null));
    }
}
