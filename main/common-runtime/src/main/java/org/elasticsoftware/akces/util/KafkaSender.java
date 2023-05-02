package org.elasticsoftware.akces.util;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.errors.ApiException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class KafkaSender {

    private KafkaSender() {
        // Utility class
    }

    public static <K,V> Future<RecordMetadata> send(Producer<K, V> producer, ProducerRecord<K, V> producerRecord) {
        return send(producer, producerRecord, null);
    }

    public static <K,V> Future<RecordMetadata> send(Producer<K, V> producer, ProducerRecord<K, V> producerRecord, Callback callback) {
        Future<RecordMetadata> future = producer.send(producerRecord, callback);
        if (future.isDone()) {
            try {
                future.get();
            } catch (InterruptedException ignored) {
                // if the future isDone(), it is a KafkaProducer.FutureFailure and it cannot throw InterruptedException
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ApiException) {
                    throw (ApiException) e.getCause();
                } else if(e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                } else {
                    throw new RuntimeException(e.getCause());
                }
            }
        }
        return future;
    }

}
