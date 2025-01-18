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
import org.elasticsoftware.cryptotrading.aggregates.account.events.AccountCreatedEvent;
import org.elasticsoftware.cryptotrading.web.dto.AccountInput;
import org.elasticsoftware.cryptotrading.web.dto.AccountOutput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v{version:1}/accounts")
public class AccountCommandController {
    private final AkcesClient akcesClient;

    public AccountCommandController(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }

    @PostMapping
    public Mono<ResponseEntity<AccountOutput>> createAccount(@RequestBody AccountInput input) {
        String userId = UUID.randomUUID().toString();
        return Mono.fromCompletionStage(akcesClient.send("TEST", input.toCommand(userId)))
                .map(List::getFirst)
                .map(domainEvent -> {
                    AccountCreatedEvent event = (AccountCreatedEvent) domainEvent;
                    AccountOutput output = new AccountOutput(event.userId(), input.country(), input.firstName(), input.lastName(), input.email());
                    return ResponseEntity.ok(output);
                });
    }
}
