package org.elasticsoftware.cryptotrading.web.dto;

import java.math.BigDecimal;

public record OrderOutput(
        String orderId,
        String marketId,
        BigDecimal size,
        BigDecimal amount,
        String clientReference
) {
}
