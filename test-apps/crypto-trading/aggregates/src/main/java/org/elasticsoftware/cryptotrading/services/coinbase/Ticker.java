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

package org.elasticsoftware.cryptotrading.services.coinbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record Ticker(
        @NotNull @JsonProperty("trade_id") Integer tradeId,
        @NotNull @JsonProperty("price") String price,
        @NotNull @JsonProperty("size") String size,
        @NotNull @JsonProperty("time") String time,
        @NotNull @JsonProperty("bid") String bid,
        @NotNull @JsonProperty("ask") String ask,
        @NotNull @JsonProperty("volume") String volume,
        @JsonProperty("rfq_volume") String rfqVolume,
        @JsonProperty("conversions_volume") String conversionsVolume
) {
}
