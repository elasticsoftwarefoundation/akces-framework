package org.elasticsoftware.akces.kafka;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;
import java.util.function.Supplier;

public class KafkaProducerFactory<K,V> extends DefaultKafkaProducerFactory<K,V> {
    public KafkaProducerFactory(Map<String, Object> configs) {
        super(configs);
    }

    public KafkaProducerFactory(Map<String, Object> configs, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        super(configs, keySerializer, valueSerializer);
    }

    public KafkaProducerFactory(Map<String, Object> configs, Serializer<K> keySerializer, Serializer<V> valueSerializer, boolean configureSerializers) {
        super(configs, keySerializer, valueSerializer, configureSerializers);
    }

    public KafkaProducerFactory(Map<String, Object> configs, Supplier<Serializer<K>> keySerializerSupplier, Supplier<Serializer<V>> valueSerializerSupplier) {
        super(configs, keySerializerSupplier, valueSerializerSupplier);
    }

    public KafkaProducerFactory(Map<String, Object> configs, Supplier<Serializer<K>> keySerializerSupplier, Supplier<Serializer<V>> valueSerializerSupplier, boolean configureSerializers) {
        super(configs, keySerializerSupplier, valueSerializerSupplier, configureSerializers);
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
