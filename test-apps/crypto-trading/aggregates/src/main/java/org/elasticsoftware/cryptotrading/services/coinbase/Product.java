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

package org.elasticsoftware.cryptotrading.services.coinbase;


import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record Product(
        @NotNull @JsonProperty("id") String id,
        @NotNull @JsonProperty("base_currency") String baseCurrency,
        @NotNull @JsonProperty("quote_currency") String quoteCurrency,
        @NotNull @JsonProperty("quote_increment") String quoteIncrement,
        @NotNull @JsonProperty("base_increment") String baseIncrement,
        @NotNull @JsonProperty("display_name") String displayName,
        @NotNull @JsonProperty("min_market_funds") String minMarketFunds,
        @NotNull @JsonProperty("margin_enabled") Boolean marginEnabled,
        @NotNull @JsonProperty("post_only") Boolean postOnly,
        @NotNull @JsonProperty("limit_only") Boolean limitOnly,
        @NotNull @JsonProperty("cancel_only") Boolean cancelOnly,
        @NotNull @JsonProperty("status") String status,
        @NotNull @JsonProperty("status_message") String statusMessage,
        @JsonProperty("trading_disabled") Boolean tradingDisabled,
        @JsonProperty("fx_stablecoin") Boolean fxStablecoin,
        @JsonProperty("max_slippage_percentage") String maxSlippagePercentage,
        @NotNull @JsonProperty("auction_mode") Boolean auctionMode,
        @JsonProperty("high_bid_limit_percentage") String highBidLimitPercentage
) {
}