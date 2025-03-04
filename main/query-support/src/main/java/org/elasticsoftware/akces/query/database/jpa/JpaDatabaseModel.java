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

package org.elasticsoftware.akces.query.database.jpa;

import org.elasticsoftware.akces.query.DatabaseModel;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED;

public class JpaDatabaseModel implements DatabaseModel {
    private final PartitionOffsetRepository repository;
    private final PlatformTransactionManager transactionManager;
    private final DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition(PROPAGATION_REQUIRED);

    public JpaDatabaseModel(PartitionOffsetRepository repository,
                          PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionManager = transactionManager;
        transactionDefinition.setIsolationLevel(ISOLATION_READ_COMMITTED);
    }

    @Override
    public Map<String, Long> getOffsets(Set<String> partitionIds) {
            return repository.findByPartitionIdIn(partitionIds).stream()
                .collect(Collectors.toMap(
                    PartitionOffset::partitionId,
                    PartitionOffset::offset
                ));
    }

    @Override
    public Object startTransaction() {
        return transactionManager.getTransaction(transactionDefinition);
    }

    @Override
    public void commitTransaction(Object transactionMarker, Map<String, Long> offsets) {
        if (!(transactionMarker instanceof TransactionStatus status)) {
            throw new IllegalArgumentException("Invalid transaction marker");
        }
        try {
            repository.saveAll(
                offsets.entrySet().stream()
                    .map(e -> new PartitionOffset(e.getKey(), e.getValue()))
                    .collect(Collectors.toList())
            );
            transactionManager.commit(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw new RuntimeException("Failed to commit offsets", e);
        }
    }
}
