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

package org.elasticsoftware.akces.query.models;

import org.elasticsoftware.akces.annotations.DatabaseModelInfo;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

//@Configuration
@ComponentScan(basePackages = {
        "org.elasticsoftware.akcestest.aggregate",
        "org.elasticsoftware.akces.query.models.wallet",
        "org.elasticsoftware.akces.query.models.account"
},excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = DatabaseModelInfo.class),
})
public class QueryModelTestConfiguration {
}
