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

package org.elasticsoftware.akces.query.reflector;

import org.elasticsoftware.akces.annotations.ReflectorEventHandler;
import org.elasticsoftware.akces.annotations.ReflectorInfo;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.query.Reflector;
import org.elasticsoftware.akcestest.aggregate.wallet.WalletCreatedEvent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ReflectorInfo(value = "TestNotificationReflector", version = 1, schemaName = "Wallet", maxRetries = 2, retryBackoffBaseMs = 100)
public class TestNotificationReflector implements Reflector<String> {

    public static final ConcurrentLinkedQueue<WalletCreatedEvent> processedEvents = new ConcurrentLinkedQueue<>();
    public static final ConcurrentLinkedQueue<String> successResults = new ConcurrentLinkedQueue<>();
    public static final ConcurrentLinkedQueue<Exception> failureExceptions = new ConcurrentLinkedQueue<>();
    public static final AtomicBoolean shouldFail = new AtomicBoolean(false);
    public static final AtomicInteger callCount = new AtomicInteger(0);

    @ReflectorEventHandler
    public String handle(WalletCreatedEvent event) {
        callCount.incrementAndGet();
        if (shouldFail.get()) {
            throw new RuntimeException("Simulated failure");
        }
        processedEvents.add(event);
        return "Notification sent for wallet: " + event.id();
    }

    @Override
    public void onSuccess(DomainEvent event, String result, CommandBus commandBus) {
        successResults.add(result);
    }

    @Override
    public void onFailure(DomainEvent event, Exception exception, CommandBus commandBus) {
        failureExceptions.add(exception);
    }

    public static void reset() {
        processedEvents.clear();
        successResults.clear();
        failureExceptions.clear();
        shouldFail.set(false);
        callCount.set(0);
    }
}
