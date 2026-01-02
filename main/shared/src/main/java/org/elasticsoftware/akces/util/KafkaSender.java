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

package org.elasticsoftware.akces.util;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.ApiException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class KafkaSender {

    private KafkaSender() {
        // Utility class
    }

    public static <K, V> Future<RecordMetadata> send(Producer<K, V> producer, ProducerRecord<K, V> producerRecord) {
        return send(producer, producerRecord, null);
    }

    public static <K, V> Future<RecordMetadata> send(Producer<K, V> producer, ProducerRecord<K, V> producerRecord, Callback callback) {
        Future<RecordMetadata> future = producer.send(producerRecord, callback);
        if (future.isDone()) {
            try {
                future.get();
            } catch (InterruptedException ignored) {
                // if the future isDone(), it is a KafkaProducer.FutureFailure and it cannot throw InterruptedException
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ApiException) {
                    throw (ApiException) e.getCause();
                } else if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                } else {
                    throw new RuntimeException(e.getCause());
                }
            }
        }
        return future;
    }

}
