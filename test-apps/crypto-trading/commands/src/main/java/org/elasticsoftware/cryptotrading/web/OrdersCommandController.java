/*
 * Copyright 2022 - 2026 The Original Authors
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
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.SellOrderCreatedEvent;
import org.elasticsoftware.cryptotrading.web.dto.BuyOrderInput;
import org.elasticsoftware.cryptotrading.web.dto.OrderOutput;
import org.elasticsoftware.cryptotrading.web.dto.SellOrderInput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/v{version:1}/accounts/{accountId}/orders")
public class OrdersCommandController {
    private final AkcesClient akcesClient;

    public OrdersCommandController(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }

    @PostMapping("/buy")
    public Mono<ResponseEntity<OrderOutput>> placeBuyOrder(
            @PathVariable("accountId") String accountId,
            @RequestBody BuyOrderInput input) {
        return Mono.fromCompletionStage(akcesClient.send("TEST", input.toCommand(accountId)))
                .map(List::getFirst)
                .map(domainEvent -> {
                    BuyOrderCreatedEvent event = (BuyOrderCreatedEvent) domainEvent;
                    OrderOutput output = new OrderOutput(
                            event.orderId(),
                            event.market(),
                            null,
                            event.amount(),
                            event.clientReference()
                    );
                    return ResponseEntity.ok(output);
                });
    }

    @PostMapping("/sell")
    public Mono<ResponseEntity<OrderOutput>> placeSellOrder(
            @PathVariable("accountId") String accountId,
            @RequestBody SellOrderInput input) {
        return Mono.fromCompletionStage(akcesClient.send("TEST", input.toCommand(accountId)))
                .map(List::getFirst)
                .map(domainEvent -> {
                    SellOrderCreatedEvent event = (SellOrderCreatedEvent) domainEvent;
                    OrderOutput output = new OrderOutput(
                            event.orderId(),
                            event.market(),
                            null,
                            event.quantity(),
                            event.clientReference()
                    );
                    return ResponseEntity.ok(output);
                });
    }
}
