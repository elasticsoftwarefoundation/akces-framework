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
import jakarta.annotation.Nonnull;

public record Product(
        @Nonnull @JsonProperty("id") String id,
        @Nonnull @JsonProperty("base_currency") String baseCurrency,
        @Nonnull @JsonProperty("quote_currency") String quoteCurrency,
        @Nonnull @JsonProperty("quote_increment") String quoteIncrement,
        @Nonnull @JsonProperty("base_increment") String baseIncrement,
        @Nonnull @JsonProperty("display_name") String displayName,
        @Nonnull @JsonProperty("min_market_funds") String minMarketFunds,
        @Nonnull @JsonProperty("margin_enabled") Boolean marginEnabled,
        @Nonnull @JsonProperty("post_only") Boolean postOnly,
        @Nonnull @JsonProperty("limit_only") Boolean limitOnly,
        @Nonnull @JsonProperty("cancel_only") Boolean cancelOnly,
        @Nonnull @JsonProperty("status") String status,
        @Nonnull @JsonProperty("status_message") String statusMessage,
        @JsonProperty("trading_disabled") Boolean tradingDisabled,
        @JsonProperty("fx_stablecoin") Boolean fxStablecoin,
        @JsonProperty("max_slippage_percentage") String maxSlippagePercentage,
        @Nonnull @JsonProperty("auction_mode") Boolean auctionMode,
        @JsonProperty("high_bid_limit_percentage") String highBidLimitPercentage
) {
}