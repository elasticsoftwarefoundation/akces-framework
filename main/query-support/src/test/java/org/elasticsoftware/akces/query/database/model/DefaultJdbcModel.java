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

package org.elasticsoftware.akces.query.database.model;

import org.elasticsoftware.akces.annotations.DatabaseModelEventHandler;
import org.elasticsoftware.akces.annotations.DatabaseModelInfo;
import org.elasticsoftware.akces.query.database.jdbc.JdbcDatabaseModel;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLException;

@DatabaseModelInfo(value = "Default", version = 1, schemaName = "default_v1")
public class DefaultJdbcModel extends JdbcDatabaseModel {
    public DefaultJdbcModel(@Autowired(required = false) PlatformTransactionManager transactionManager,@Autowired(required = false) JdbcTemplate jdbcTemplate) throws SQLException {
        super(transactionManager, jdbcTemplate);
    }

    @DatabaseModelEventHandler
    public void handle(AccountCreatedEvent event) {
        jdbcTemplate.update("""
            INSERT INTO account (user_id, country, first_name, last_name, email)
            VALUES (?, ?, ?, ?, ?)
            """,
            event.userId(),
            event.country(),
            event.firstName(),
            event.lastName(),
            event.email()
        );
    }
}
