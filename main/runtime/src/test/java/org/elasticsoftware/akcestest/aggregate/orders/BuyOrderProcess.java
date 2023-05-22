package org.elasticsoftware.akcestest.aggregate.orders;

import org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

public record BuyOrderProcess(String orderId, FxMarket market, BigDecimal quantity, BigDecimal limitPrice, String clientReference) implements OrderProcess {
    @Override
    public String getProcessId() {
        return orderId();
    }

    @Override
    public DomainEvent handle(InsufficientFundsErrorEvent error) {
        return new BuyOrderRejectedEvent(error.walletId(), orderId(), clientReference());
    }
}
