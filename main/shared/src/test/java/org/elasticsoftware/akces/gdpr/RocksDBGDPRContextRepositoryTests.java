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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.elasticsoftware.akces.protocol.GDPRKeyRecord;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class RocksDBGDPRContextRepositoryTests {
    @Test
    public void testWriteInTransaction() {
        ProtocolRecordSerde serde = new ProtocolRecordSerde();
        GDPRContextRepository repository = new RocksDBGDPRContextRepository(
                "target/rocksdb",
                "2",
                "Akces-GDPRKeys",
                serde.serializer(),
                serde.deserializer());

        repository.process(List.of(new ConsumerRecord<>(
                "Akces-GDPRKeys",
                2,
                0,
                "4117b11f-3dde-4b71-b80c-fa20a12d9add",
                new GDPRKeyRecord(
                        "TEST_TENANT",
                        "4117b11f-3dde-4b71-b80c-fa20a12d9add",
                        GDPRKeyUtils.createKey().getEncoded()))));

        Assertions.assertTrue(repository.exists("4117b11f-3dde-4b71-b80c-fa20a12d9add"));
    }
}
