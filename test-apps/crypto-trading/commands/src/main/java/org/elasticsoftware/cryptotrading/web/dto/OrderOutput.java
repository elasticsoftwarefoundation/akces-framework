package org.elasticsoftware.cryptotrading.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.elasticsoftware.cryptotrading.aggregates.orders.data.CryptoMarket;

import java.math.BigDecimal;

public record OrderOutput(
        String orderId,
        CryptoMarket market,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal size,
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal amount,
        String clientReference
) {
}
