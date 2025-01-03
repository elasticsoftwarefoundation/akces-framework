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

package org.elasticsoftware.akces.gdpr;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public final class NoopGDPRContext implements GDPRContext {
    private final String aggregateId;

    public NoopGDPRContext(@Nonnull String aggregateId) {
        this.aggregateId = aggregateId;
    }

    @Nullable
    @Override
    public String encrypt(@Nullable String data) {
        return data;
    }

    @Nullable
    @Override
    public String decrypt(@Nullable String encryptedData) {
        return encryptedData;
    }

    @Nonnull
    @Override
    public String getAggregateId() {
        return aggregateId;
    }
}
