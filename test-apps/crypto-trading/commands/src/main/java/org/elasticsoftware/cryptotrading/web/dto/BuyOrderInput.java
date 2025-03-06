package org.elasticsoftware.cryptotrading.web.dto;

import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceBuyOrderCommand;

import java.math.BigDecimal;

public record BuyOrderInput(
        String marketId,
        BigDecimal size,
        BigDecimal amount,
        String clientReference
) {
    public PlaceBuyOrderCommand toCommand(String accountId) {
        return new PlaceBuyOrderCommand(
                accountId,
                marketId,
                size,
                amount,
                clientReference
        );
    }
}
