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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;

public class GDPRContextTests {
    @Test
    public void testEncryptDecrypt() {
        GDPRContext ctx = new EncryptingGDPRContext("ef234add-e0df-4769-b5f4-612a3207bad3",GDPRKeyUtils.createKey().getEncoded(), true);
        String encryptedData = ctx.encrypt("test");
        System.out.println(encryptedData);
        String decryptedData = ctx.decrypt(encryptedData);
        Assertions.assertEquals("test", decryptedData);
    }

    @Test
    public void testEncrypt() {
        GDPRContext ctx = new EncryptingGDPRContext("ef234add-e0df-4769-b5f4-612a3207bad3",GDPRKeyUtils.createKey().getEncoded(), true);
        String name = ctx.encrypt("Jasin Terlouw");
        String street = ctx.encrypt("Gershwinstraat 125");
        System.out.println("name: " + name);
        System.out.println("street: " + street);
        Assertions.assertEquals("Jasin Terlouw", ctx.decrypt(name));
        Assertions.assertEquals("Gershwinstraat 125", ctx.decrypt(street));
    }

    @Test
    public void testEncryptForTests() {
        GDPRContext ctx = new EncryptingGDPRContext("ef234add-e0df-4769-b5f4-612a3207bad3",GDPRKeyUtils.createKey().getEncoded(), true);
        String firstName = ctx.encrypt("Fahim");
        String lastName = ctx.encrypt("Zuijderwijk");
        String email = ctx.encrypt("FahimZuijderwijk@jourrapide.com");
        System.out.println(firstName);
        System.out.println(lastName);
        System.out.println(email);
        Assertions.assertEquals("Fahim", ctx.decrypt(firstName));
        Assertions.assertEquals("Zuijderwijk", ctx.decrypt(lastName));
        Assertions.assertEquals("FahimZuijderwijk@jourrapide.com", ctx.decrypt(email));
    }

    @Test
    public void testBadPaddingException() {
        String aggregateId = "47db2418-dd10-11ed-afa1-0242ac120012";
        String encrypted = "CnTg8ppy-GCWYLHEB4Ia9Q==";
        byte[] key = {90, -73, -103, -5, 40, -102, -103, 86, -29, -13, -30, -96, 109, -7, -61, 83, 58, 93, 60, -37, -54, -82, 24, 118, 53, 13, 32, 28, -68, -30, 63, -16};

        GDPRContext ctx = new EncryptingGDPRContext(aggregateId,key,true);

        ctx.decrypt(encrypted);

        String encryptedFirstName = ctx.encrypt("Fahim");
        String descryptedFirstName = ctx.decrypt(encryptedFirstName);

        Assertions.assertEquals("Fahim", descryptedFirstName);
    }

    @Test
    public void testECBEncryption() {
        String aggregateId = "user-1203847939";
        GDPRContext ctx = new EncryptingGDPRContext(aggregateId, GDPRKeyUtils.createKey().getEncoded(), false);

        String encryptedFirstName = ctx.encrypt("Fahim");
        String descryptedFirstName = ctx.decrypt(encryptedFirstName);

        Assertions.assertEquals("Fahim", descryptedFirstName);
    }

    @Test
    public void testECBEncryptionWithOtherContext() {
        String aggregateId = "user-1203847939";
        SecretKeySpec secretKey = GDPRKeyUtils.createKey();
        GDPRContext one = new EncryptingGDPRContext(aggregateId, secretKey.getEncoded(), false);
        GDPRContext two = new EncryptingGDPRContext(aggregateId, secretKey.getEncoded(), false);

        String encryptedFirstName = one.encrypt("Fahim");
        String decryptedFirstName = two.decrypt(encryptedFirstName);

        Assertions.assertEquals("Fahim", decryptedFirstName);
    }
}
