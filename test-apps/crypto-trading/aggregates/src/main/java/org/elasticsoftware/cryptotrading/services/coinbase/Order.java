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

import java.time.ZonedDateTime;

public record Order(
        @Nonnull @JsonProperty("id") String id,
        @JsonProperty("price") String price,
        @JsonProperty("size") String size,
        @Nonnull @JsonProperty("product_id") String productId,
        @JsonProperty("profile_id") String profileId,
        @Nonnull @JsonProperty("side") String side,
        @JsonProperty("funds") String funds,
        @JsonProperty("specified_funds") String specifiedFunds,
        @Nonnull @JsonProperty("type") String type,
        @JsonProperty("time_in_force") String timeInForce,
        @JsonProperty("expire_time") ZonedDateTime expireTime,
        @Nonnull @JsonProperty("post_only") Boolean postOnly,
        @Nonnull @JsonProperty("created_at") ZonedDateTime createdAt,
        @JsonProperty("done_at") ZonedDateTime doneAt,
        @JsonProperty("done_reason") String doneReason,
        @JsonProperty("reject_reason") String rejectReason,
        @Nonnull @JsonProperty("fill_fees") String fillFees,
        @Nonnull @JsonProperty("filled_size") String filledSize,
        @JsonProperty("executed_value") String executedValue,
        @Nonnull @JsonProperty("status") String status,
        @Nonnull @JsonProperty("settled") Boolean settled,
        @JsonProperty("stop") String stop,
        @JsonProperty("stop_price") String stopPrice,
        @JsonProperty("funding_amount") String fundingAmount,
        @JsonProperty("client_oid") String clientOid,
        @JsonProperty("market_type") String marketType,
        @JsonProperty("max_floor") String maxFloor,
        @JsonProperty("secondary_order_id") String secondaryOrderId,
        @JsonProperty("stop_limit_price") String stopLimitPrice
) {
}
