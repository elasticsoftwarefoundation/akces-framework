package org.elasticsoftware.akces.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

public class CustomKafkaConsumerFactory<K,V> extends DefaultKafkaConsumerFactory<K,V> {
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
