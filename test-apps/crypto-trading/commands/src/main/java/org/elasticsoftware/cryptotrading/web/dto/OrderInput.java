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

package org.elasticsoftware.cryptotrading.web.dto;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.cryptotrading.aggregates.orders.data.CryptoMarket;

import java.math.BigDecimal;

public record OrderInput(@NotNull CryptoMarket market,
                         @NotNull BigDecimal amount,
                         @NotNull String clientReference) {
}
