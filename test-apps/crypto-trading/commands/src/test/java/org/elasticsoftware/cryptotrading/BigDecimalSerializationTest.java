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

package org.elasticsoftware.cryptotrading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.cryptotrading.web.dto.BalanceOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

public class BigDecimalSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new BigDecimalSerializer());
        objectMapper.registerModule(module);
    }

    @Test
    void testBigDecimalSerializationAsString() throws Exception {
        BalanceOutput balance = new BalanceOutput(
            "wallet-123",
            "EUR",
            new BigDecimal("100.50"),
            new BigDecimal("250.75")
        );

        String json = objectMapper.writeValueAsString(balance);
        
        // Verify BigDecimal fields are serialized as strings, not numbers
        assertThat(json).contains("\"100.50\"");
        assertThat(json).contains("\"250.75\"");
        // Ensure they are NOT serialized as numbers (no decimal point without quotes)
        assertThat(json).doesNotContain(":100.50");
        assertThat(json).doesNotContain(":250.75");
    }

    @Test
    void testBigDecimalWithScientificNotation() throws Exception {
        BalanceOutput balance = new BalanceOutput(
            "wallet-456",
            "BTC",
            new BigDecimal("0.00000001"),
            new BigDecimal("1000000000")
        );

        String json = objectMapper.writeValueAsString(balance);
        
        // Verify plain string representation without scientific notation
        assertThat(json).contains("\"0.00000001\"");
        assertThat(json).contains("\"1000000000\"");
        // Should not have scientific notation like 1.0E-8 or 1.0e9
        assertThat(json).doesNotContain("E-");
        assertThat(json).doesNotContain("E+");
        assertThat(json).doesNotContain("e-");
        assertThat(json).doesNotContain("e+");
    }
}
