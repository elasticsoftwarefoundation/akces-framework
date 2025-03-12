package org.elasticsoftware.cryptotrading.web.dto;

import org.elasticsoftware.cryptotrading.aggregates.orders.data.CryptoMarket;

import java.math.BigDecimal;

public record OrderOutput(
        String orderId,
        CryptoMarket market,
        BigDecimal size,
        BigDecimal amount,
        String clientReference
) {
}
