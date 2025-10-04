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

package org.elasticsoftware.akces.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndexParamsTest {

    @Test
    void testRecordCreation() {
        IndexParams params = new IndexParams("TestIndex", "testKey", true);
        assertEquals("TestIndex", params.indexName());
        assertEquals("testKey", params.indexKey());
        assertTrue(params.createIndex());
    }

    @Test
    void testRecordWithNoIndex() {
        IndexParams params = new IndexParams("TestIndex", "testKey", false);
        assertEquals("TestIndex", params.indexName());
        assertEquals("testKey", params.indexKey());
        assertFalse(params.createIndex());
    }

    @Test
    void testRecordEquality() {
        IndexParams params1 = new IndexParams("TestIndex", "testKey", true);
        IndexParams params2 = new IndexParams("TestIndex", "testKey", true);
        assertEquals(params1, params2);
        assertEquals(params1.hashCode(), params2.hashCode());
    }

    @Test
    void testRecordInequality() {
        IndexParams params1 = new IndexParams("TestIndex1", "testKey", true);
        IndexParams params2 = new IndexParams("TestIndex2", "testKey", true);
        assertNotEquals(params1, params2);
    }
}
