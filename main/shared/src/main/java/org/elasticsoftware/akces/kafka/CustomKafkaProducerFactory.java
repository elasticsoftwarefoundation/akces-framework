/*
 * Copyright 2022 - 2023 The Original Authors
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

package org.elasticsoftware.akces.kafka;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

public class CustomKafkaProducerFactory<K,V> extends DefaultKafkaProducerFactory<K,V> {

    public CustomKafkaProducerFactory(Map<String, Object> configs, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        super(configs, keySerializer, valueSerializer);
    }

    @Override
    protected Producer<K, V> createTransactionalProducer(String transactionId) {
        // we treat the prefix as the full transaction id
        // TODO: take a look at the client id
        Producer<K, V> newProducer = createRawProducer(getTxProducerConfigs(transactionId));
        try {
            newProducer.initTransactions();
        }
        catch (RuntimeException ex) {
            try {
                newProducer.close(ProducerFactory.DEFAULT_PHYSICAL_CLOSE_TIMEOUT);
            }
            catch (RuntimeException ex2) {
                KafkaException newEx = new KafkaException("initTransactions() failed and then close() failed", ex);
                newEx.addSuppressed(ex2);
                throw newEx; // NOSONAR - lost stack trace
            }
            throw new KafkaException("initTransactions() failed", ex);
        }
        return newProducer;
    }
}
