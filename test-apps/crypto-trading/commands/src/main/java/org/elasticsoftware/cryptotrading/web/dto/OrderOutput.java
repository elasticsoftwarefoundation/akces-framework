package org.elasticsoftware.cryptotrading.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.cryptotrading.aggregates.orders.data.CryptoMarket;

import java.math.BigDecimal;

public record OrderOutput(
        String orderId,
        CryptoMarket market,
        @JsonSerialize(using = BigDecimalSerializer.class)
        BigDecimal size,
        @JsonSerialize(using = BigDecimalSerializer.class)
        BigDecimal amount,
        String clientReference
) {
}
