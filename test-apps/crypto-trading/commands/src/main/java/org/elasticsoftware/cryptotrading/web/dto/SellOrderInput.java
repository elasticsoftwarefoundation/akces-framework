package org.elasticsoftware.cryptotrading.web.dto;

import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceSellOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.data.CryptoMarket;

import java.math.BigDecimal;

public record SellOrderInput(
        String marketId,
        BigDecimal quantity,
        String clientReference
) {
    public PlaceSellOrderCommand toCommand(String accountId) {
        return new PlaceSellOrderCommand(
                accountId,
                CryptoMarket.fromId(marketId),
                quantity,
                clientReference
        );
    }
}
