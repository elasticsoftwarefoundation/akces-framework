/*
 * Copyright 2022 - 2025 The Original Authors
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

package org.elasticsoftware.cryptotrading.web;

import org.elasticsoftware.akces.client.AkcesClient;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.BalanceCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.WalletCreditedEvent;
import org.elasticsoftware.cryptotrading.web.dto.BalanceOutput;
import org.elasticsoftware.cryptotrading.web.dto.CreateBalanceInput;
import org.elasticsoftware.cryptotrading.web.dto.CreditWalletInput;
import org.elasticsoftware.cryptotrading.web.errors.ErrorEventException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/v{version:1}/wallets")
public class WalletCommandController {
    private final AkcesClient akcesClient;

    public WalletCommandController(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }

    @PostMapping("/{walletId}/balances/{currency}/credit")
    public Mono<ResponseEntity<BalanceOutput>> creditBalance(@PathVariable("walletId") String walletId,
                                                             @PathVariable("currency") String currency,
                                                             @RequestBody CreditWalletInput input) {
        return Mono.fromCompletionStage(akcesClient.send("TEST", input.toCommand(walletId, currency)))
                .map(List::getFirst)
                .handle((domainEvent, sink) -> {
                    if (domainEvent instanceof WalletCreditedEvent) {
                        sink.next(ResponseEntity.ok(BalanceOutput.from((WalletCreditedEvent) domainEvent)));
                    } else {
                        sink.error(new ErrorEventException((ErrorEvent) domainEvent));
                    }
                });
    }

    @PostMapping("/{walletId}/balances")
    public Mono<ResponseEntity<BalanceOutput>> createBalance(@PathVariable("walletId") String walletId, @RequestBody CreateBalanceInput input) {
        return Mono.fromCompletionStage(akcesClient.send("TEST", input.toCommand(walletId)))
                .map(List::getFirst)
                .handle((domainEvent, sink) -> {
                    if (domainEvent instanceof BalanceCreatedEvent balanceCreatedEvent) {
                        sink.next(ResponseEntity.ok(BalanceOutput.from(balanceCreatedEvent)));
                    } else if (domainEvent instanceof ErrorEvent) {
                        sink.error(new ErrorEventException((ErrorEvent) domainEvent));
                    } else {
                        // we received an unexpected event
                        // TODO: do we need to handle this?
                    }
                });
    }
}
