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

package org.elasticsoftware.akces.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class BigDecimalSerializerTests {

    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new BigDecimalSerializer());
        objectMapper.registerModule(module);
    }

    @Test
    public void testSerializeSimpleBigDecimal() throws IOException {
        BigDecimal value = new BigDecimal("123.45");
        String json = objectMapper.writeValueAsString(value);
        
        assertEquals("\"123.45\"", json);
    }

    @Test
    public void testSerializeBigDecimalWithTrailingZeros() throws IOException {
        BigDecimal value = new BigDecimal("100.00");
        String json = objectMapper.writeValueAsString(value);
        
        // Should use toPlainString() which preserves format
        assertEquals("\"100.00\"", json);
    }

    @Test
    public void testSerializeBigDecimalWithScientificNotation() throws IOException {
        BigDecimal value = new BigDecimal("1E+10");
        String json = objectMapper.writeValueAsString(value);
        
        // toPlainString() should expand scientific notation
        assertEquals("\"10000000000\"", json);
    }

    @Test
    public void testSerializeZero() throws IOException {
        BigDecimal value = BigDecimal.ZERO;
        String json = objectMapper.writeValueAsString(value);
        
        assertEquals("\"0\"", json);
    }

    @Test
    public void testSerializeNegativeValue() throws IOException {
        BigDecimal value = new BigDecimal("-999.99");
        String json = objectMapper.writeValueAsString(value);
        
        assertEquals("\"-999.99\"", json);
    }

    @Test
    public void testSerializeVeryLargeNumber() throws IOException {
        BigDecimal value = new BigDecimal("999999999999999999.99");
        String json = objectMapper.writeValueAsString(value);
        
        assertEquals("\"999999999999999999.99\"", json);
    }

    @Test
    public void testSerializeVerySmallNumber() throws IOException {
        BigDecimal value = new BigDecimal("0.000000000001");
        String json = objectMapper.writeValueAsString(value);
        
        assertEquals("\"0.000000000001\"", json);
    }

    @Test
    public void testSerializeInObjectContext() throws IOException {
        TestObject obj = new TestObject(new BigDecimal("42.50"));
        String json = objectMapper.writeValueAsString(obj);
        
        assertTrue(json.contains("\"42.50\""));
    }

    static class TestObject {
        public BigDecimal amount;
        
        public TestObject(BigDecimal amount) {
            this.amount = amount;
        }
    }
}
