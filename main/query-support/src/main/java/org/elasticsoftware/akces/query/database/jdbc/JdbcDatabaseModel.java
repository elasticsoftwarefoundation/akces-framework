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

package org.elasticsoftware.akces.query.database.jdbc;

import jakarta.annotation.Nullable;
import org.elasticsoftware.akces.query.DatabaseModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class JdbcDatabaseModel implements DatabaseModel {
    private final PlatformTransactionManager transactionManager;
    protected final JdbcTemplate jdbcTemplate;
    private final DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);
    private final TransactionTemplate transactionTemplate;
    private String databaseType;

    public JdbcDatabaseModel(PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
        this.transactionManager = transactionManager;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionDefinition.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate = new TransactionTemplate(transactionManager, transactionDefinition);
    }

    @Override
    public Map<String, Long> getOffsets(Set<String> partitionIds) {
        return transactionTemplate.execute(status -> {
            String sql = "SELECT partition_id, record_offset FROM partition_offsets WHERE partition_id IN (%s)"
                    .formatted(String.join(",", Collections.nCopies(partitionIds.size(), "?")));

            return jdbcTemplate.query(
                sql,
                ps -> {
                    int idx = 1;
                    for (String partitionId : partitionIds) {
                        ps.setString(idx++, partitionId);
                    }
                },
                (rs, rowNum) -> Map.entry(
                    rs.getString("partition_id"),
                    rs.getLong("record_offset")
                )
            ).stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
            ));
        });
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
            detectDatabaseType();

            String sql = getUpsertSql("partition_offsets", "partition_id", "record_offset");

            jdbcTemplate.batchUpdate(
                sql,
                offsets.entrySet().stream()
                    .map(entry -> new Object[]{entry.getKey(), entry.getValue()})
                    .collect(Collectors.toList())
            );

            transactionManager.commit(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw new RuntimeException("Failed to commit offsets", e);
        }
    }

    private void detectDatabaseType() throws SQLException {
        if(databaseType == null && jdbcTemplate.getDataSource() != null) {
            try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                databaseType = metadata.getDatabaseProductName().toLowerCase();
            }
        }

    }

    private String getUpsertSql(String tableName, String keyColumn, String valueColumn) {
        // Choose SQL dialect based on database product
        return switch (databaseType) {
            case "postgresql" -> """
                INSERT INTO %s (%s, %s)
                VALUES (?, ?)
                ON CONFLICT (%s) DO UPDATE
                SET %s = EXCLUDED.%s
                """.formatted(tableName, keyColumn, valueColumn, keyColumn, valueColumn, valueColumn);

            case "mysql", "mariadb" -> """
                INSERT INTO %s (%s, %s)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE
                %s = VALUES(%s)
                """.formatted(tableName, keyColumn, valueColumn, valueColumn, valueColumn);

            case "oracle", "microsoft sql server" -> """
                MERGE INTO %s target
                USING (VALUES (?, ?)) AS source (%s, %s)
                ON target.%s = source.%s
                WHEN MATCHED THEN
                    UPDATE SET %s = source.%s
                WHEN NOT MATCHED THEN
                    INSERT (%s, %s)
                    VALUES (source.%s, source.%s)
                """.formatted(tableName, keyColumn, valueColumn,
                            keyColumn, keyColumn,
                            valueColumn, valueColumn,
                            keyColumn, valueColumn,
                            keyColumn, valueColumn);

            default -> throw new UnsupportedOperationException("Unsupported database: " + databaseType);
        };
    }
}
