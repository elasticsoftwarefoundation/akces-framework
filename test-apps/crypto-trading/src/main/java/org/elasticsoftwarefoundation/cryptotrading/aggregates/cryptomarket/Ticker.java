package org.elasticsoftwarefoundation.cryptotrading.aggregates.cryptomarket;

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
) {}
