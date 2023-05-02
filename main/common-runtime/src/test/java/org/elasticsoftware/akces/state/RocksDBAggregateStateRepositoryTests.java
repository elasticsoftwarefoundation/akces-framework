package org.elasticsoftware.akces.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.WalletState;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RocksDBAggregateStateRepositoryTests {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Future<RecordMetadata> producerResponse = mock(Future.class);

    @Test
    public void testCreate() throws RocksDBException, IOException {
        try (RocksDBAggregateStateRepository repository =
                     new RocksDBAggregateStateRepository("/tmp/rocksdb",
                             "Wallet-AggregateState-0",
                             "Wallet-AggregateState",
                             serde.serializer(),
                             serde.deserializer())) {
            AggregateStateRecord record = repository.get("1234");
            Assertions.assertNull(record);
        }
    }

    @Test
    public void testSingleUpdate() throws RocksDBException, IOException, ExecutionException, InterruptedException {
        try (RocksDBAggregateStateRepository repository =
                     new RocksDBAggregateStateRepository("/tmp/rocksdb",
                             "Wallet-AggregateState-0",
                             "Wallet-AggregateState",
                             serde.serializer(),
                             serde.deserializer())) {
            String id = "3f61ae34-0945-4d5a-89c6-ee2088a83315";
            WalletState state = new WalletState(id, "USD", BigDecimal.ZERO);
            AggregateStateRecord record = new AggregateStateRecord(
                    "AKCES",
                    "Wallet",
                    1,
                    objectMapper.writeValueAsBytes(state),
                    PayloadEncoding.JSON,
                    id,
                    UUID.randomUUID().toString(),
                    1);
            repository.prepare(record, producerResponse);

            when(producerResponse.get()).thenReturn(new RecordMetadata(new TopicPartition("Wallet-AggregateState", 0), 12, 0, System.currentTimeMillis(), 16, 345));
            repository.commit();
            // we should have a record now
            AggregateStateRecord result = repository.get(id);
            Assertions.assertNotNull(result);
            // offset should be set to 12
            Assertions.assertEquals(12, repository.getOffset());
        }
    }
}
