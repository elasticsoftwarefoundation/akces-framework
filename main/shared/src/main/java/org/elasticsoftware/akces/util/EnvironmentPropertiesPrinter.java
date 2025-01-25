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

package org.elasticsoftware.akces.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Collection;

public class EnvironmentPropertiesPrinter {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentPropertiesPrinter.class);

    @EventListener
    public void handleContextRefreshed(ContextRefreshedEvent event) {
        ConfigurableEnvironment env = (ConfigurableEnvironment) event.getApplicationContext().getEnvironment();
        logger.info("******* Environment Properties *******");
        env.getPropertySources()
                .stream()
                .filter(ps -> ps instanceof MapPropertySource)
                .map(ps -> ((MapPropertySource) ps).getSource().keySet())
                .flatMap(Collection::stream)
                .distinct()
                .filter(key -> key.startsWith("akces.") ||
                        key.startsWith("spring.") ||
                        key.startsWith("management.") ||
                        key.startsWith("server."))
                .sorted()
                .forEach(key -> logger.info("{}={}", key, env.getProperty(key)));
        logger.info("**************************************");
    }
}
