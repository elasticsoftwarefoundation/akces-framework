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

package org.elasticsoftware.akces.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

public class CustomKafkaConsumerFactory<K, V> extends DefaultKafkaConsumerFactory<K, V> {
    public CustomKafkaConsumerFactory(Map<String, Object> configs, Deserializer<K> keyDeserializer, Deserializer<V> valueDeserializer) {
        super(configs, keyDeserializer, valueDeserializer);
    }

    @Override
    protected Consumer<K, V> createKafkaConsumer(Map<String, Object> configProps) {
        // add the group.instance.id property to the same value as the client.id
        configProps.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, configProps.get(ConsumerConfig.CLIENT_ID_CONFIG));
        return super.createKafkaConsumer(configProps);
    }
}
