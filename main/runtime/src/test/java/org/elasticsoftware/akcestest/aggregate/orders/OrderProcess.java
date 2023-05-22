package org.elasticsoftware.akcestest.aggregate.orders;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.processmanager.AkcesProcess;

import java.math.BigDecimal;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BuyOrderProcess.class, name = "BUY")
})
public sealed interface OrderProcess extends AkcesProcess permits BuyOrderProcess {
    String orderId();

    FxMarket market();

    BigDecimal quantity();

    BigDecimal limitPrice();

    String clientReference();

    DomainEvent handle(InsufficientFundsErrorEvent error);
}
