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

package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class InMemoryAggregateStateRepositoryFactoryTest {

    @Test
    void testCreate() {
        InMemoryAggregateStateRepositoryFactory factory = 
            new InMemoryAggregateStateRepositoryFactory();
        
        AggregateRuntime aggregateRuntime = mock(AggregateRuntime.class);
        
        AggregateStateRepository repository = factory.create(aggregateRuntime, 0);
        
        assertNotNull(repository);
        assertTrue(repository instanceof InMemoryAggregateStateRepository);
    }

    @Test
    void testCreateMultipleRepositories() {
        InMemoryAggregateStateRepositoryFactory factory = 
            new InMemoryAggregateStateRepositoryFactory();
        
        AggregateRuntime aggregateRuntime = mock(AggregateRuntime.class);
        
        AggregateStateRepository repository1 = factory.create(aggregateRuntime, 0);
        AggregateStateRepository repository2 = factory.create(aggregateRuntime, 1);
        
        assertNotNull(repository1);
        assertNotNull(repository2);
        assertNotSame(repository1, repository2);
    }
}
