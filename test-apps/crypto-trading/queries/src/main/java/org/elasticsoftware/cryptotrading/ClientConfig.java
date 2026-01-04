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

package org.elasticsoftware.cryptotrading;

import org.elasticsoftware.cryptotrading.web.dto.Jackson3BigDecimalSerializer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;

@Configuration
@ComponentScan(basePackages = {
        "org.elasticsoftware.cryptotrading.web",
        "org.elasticsoftware.cryptotrading.query",
        "org.elasticsoftware.cryptotrading.services"
})
@EnableJdbcRepositories(basePackages = {"org.elasticsoftware.cryptotrading.query.jdbc"})
@PropertySource("classpath:akces-framework.properties")
public class ClientConfig {
    @Bean
    JsonMapperBuilderCustomizer jacksonCustomizer() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(BigDecimal.class, new Jackson3BigDecimalSerializer());
        return builder -> builder.addModule(module);
    }

    @Bean("coinbaseWebClient")
    public WebClient coinbaseWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.exchange.coinbase.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * see https://github.com/spring-projects/spring-boot/issues/48240
     * see https://github.com/spring-projects/spring-boot/issues/47781
     *
     * @return the Postgres dialect
     */
    @Bean
    JdbcPostgresDialect jdbcDialect() {
        return JdbcPostgresDialect.INSTANCE;
    }
}
