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

import org.elasticsoftware.akces.query.models.QueryModelIdNotFoundException;
import org.elasticsoftware.akces.query.models.QueryModelNotFoundException;
import org.elasticsoftware.akces.query.models.QueryModels;
import org.elasticsoftware.cryptotrading.query.WalletQueryModel;
import org.elasticsoftware.cryptotrading.query.WalletQueryModelState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v{version:1}/wallets")
public class WalletQueryController {
    private final QueryModels queryModels;

    public WalletQueryController(QueryModels queryModels) {
        this.queryModels = queryModels;
    }

    @GetMapping("/{walletId}")
    Mono<ResponseEntity<WalletQueryModelState>> getWallet(@PathVariable("walletId") String walletId) {
        return Mono.fromCompletionStage(queryModels.getHydratedState(WalletQueryModel.class, walletId))
            .map(ResponseEntity::ok)
            .onErrorResume(throwable -> {
                if (throwable instanceof QueryModelNotFoundException || throwable instanceof QueryModelIdNotFoundException) {
                    return Mono.just(ResponseEntity.notFound().build());
                } else {
                    return Mono.just(ResponseEntity.internalServerError().build());
                }
            });
    }
}
