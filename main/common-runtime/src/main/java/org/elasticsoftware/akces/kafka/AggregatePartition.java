package org.elasticsoftware.akces.kafka;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.*;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.state.AggregateStateRepository;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.elasticsoftware.akces.kafka.AggregatePartitionState.*;

public class AggregatePartition {
    private final Consumer<String, ProtocolRecord> consumer;
    private final Producer<String, ProtocolRecord> producer;
    private final AggregateRuntime runtime;
    private final AggregateStateRepository stateRepository;
    private final Integer id;
    private final TopicPartition commandPartition;
    private final TopicPartition domainEventPartition;
    private final TopicPartition statePartition;
    private final List<TopicPartition> externalEventPartitions;

    private AggregatePartitionState processState;
    private Long initializedEndOffset = null;

    public AggregatePartition(Consumer<String, ProtocolRecord> consumer,
                              Producer<String, ProtocolRecord> producer,
                              AggregateRuntime runtime,
                              AggregateStateRepository stateRepository,
                              Integer id,
                              TopicPartition commandPartition,
                              TopicPartition domainEventPartition,
                              TopicPartition statePartition,
                              List<TopicPartition> externalEventPartitions) {
        this.consumer = consumer;
        this.producer = producer;
        this.runtime = runtime;
        this.stateRepository = stateRepository;
        this.id = id;
        this.commandPartition = commandPartition;
        this.domainEventPartition = domainEventPartition;
        this.statePartition = statePartition;
        this.externalEventPartitions = externalEventPartitions;
        this.processState = INITIALIZING;
    }

    private void send(ProtocolRecord protocolRecord) {
        if(protocolRecord instanceof AggregateStateRecord asr) {
            // send to topic
            producer.send(new ProducerRecord<>(statePartition.topic(), statePartition.partition(), asr.aggregateId(), asr));
            // prepare (cache) for commit
            stateRepository.prepare(asr);
        } else if(protocolRecord instanceof DomainEventRecord der) {
            producer.send(new ProducerRecord<>(domainEventPartition.topic(), domainEventPartition.partition(), der.aggregateId(), der));
        } else if(protocolRecord instanceof CommandRecord cr) {
            // commands should be sent via the CommandBus since it needs to figure out the topic
            // producer.send(new ProducerRecord<>(commandPartition.topic(), commandPartition.partition(), cr.aggregateId(), cr));
            // this is a framework programmer error, it should never happen
            throw new IllegalArgumentException("""
                    send(ProtocolRecord) should not be used for CommandRecord type.
                    Use send(commandRecord,commandPartition) instead""");
        }
    }

    private void send(CommandRecord cr, TopicPartition commandPartition) {
        producer.send(new ProducerRecord<>(commandPartition.topic(), commandPartition.partition(), cr.aggregateId(), cr));
    }

    private void handleCommand(CommandRecord commandRecord) {
        try {
            runtime.handleCommandRecord(commandRecord, this::send, () -> stateRepository.get(commandRecord.aggregateId()));
        } catch (IOException e) {
            // TODO need to raise a (built-in) ErrorEvent here
        }
    }

    private void handleExternalEvent(DomainEventRecord eventRecord) {
        try {
            runtime.handleExternalDomainEventRecord(eventRecord, this::send, () -> stateRepository.get(eventRecord.aggregateId()));
        } catch (IOException e) {
            // TODO need to raise a (built-in) ErrorEvent here
        }
    }

    public void process() {
        if(processState == PROCESSING) {
            try {
                ConsumerRecords<String, ProtocolRecord> allRecords = consumer.poll(Duration.ZERO);
                if(!allRecords.isEmpty()) {
                    processRecords(allRecords);
                }
            } catch(WakeupException | InterruptException ignore) {
                // non-fatal. ignore
            }  catch(ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
                // For transactional producers, this is a fatal error and you should close the producer.   
            } catch(KafkaException e) {
                // fatal
            }
        } else if(processState == LOADING_STATE) {
            ConsumerRecords<String, ProtocolRecord> stateRecords = consumer.poll(Duration.ZERO);
            stateRecords.iterator().forEachRemaining(stateRecord -> {
                if(stateRecord.value() != null) {
                    stateRepository.add((AggregateStateRecord) stateRecord.value(), stateRecord.offset());
                } else {
                    // remove from repository
                    stateRepository.remove((AggregateStateRecord) stateRecord.value(), stateRecord.offset());
                }
            });
            // stop condition
            if(stateRecords.isEmpty() && initializedEndOffset <= consumer.position(statePartition)) {
                // done loading the state, enable the other topics
                consumer.resume(Stream.concat(Stream.of(commandPartition, domainEventPartition), externalEventPartitions.stream()).toList());
                // and move to processing state
                processState = PROCESSING;
            }
        } else if(processState == INITIALIZING) {
            // find the right offset to start reading the state from
            long repositoryOffset = stateRepository.getOffset();
            if(repositoryOffset >= 0) {
                consumer.seek(statePartition, stateRepository.getOffset() + 1);
            } else {
                consumer.seekToBeginning(singletonList(statePartition));
            }
            // find the end offset so we know when to stop
            initializedEndOffset = consumer.endOffsets(singletonList(statePartition)).values().stream().findFirst().orElse(0L);
            // special case, there is no data yet so no need to load anything
            if(initializedEndOffset == 0L) {
                // go immediately to processing
                processState = PROCESSING;
            } else {
                // we need to load the state first, so pause the other topics
                consumer.pause(Stream.concat(Stream.of(commandPartition, domainEventPartition), externalEventPartitions.stream()).toList());
                processState = LOADING_STATE;
            }
        }
    }

    private void processRecords(ConsumerRecords<String, ProtocolRecord> allRecords) {
        try {
            // start a transaction
            producer.beginTransaction();
            Map<TopicPartition, Long> offsets = new HashMap<>();
            // first handle commands
            allRecords.records(commandPartition)
                    .forEach(commandRecord -> {
                        handleCommand((CommandRecord) commandRecord.value());
                        offsets.put(commandPartition, commandRecord.offset());
                    });
            // then external events
            externalEventPartitions
                    .forEach(externalEventPartition -> allRecords.records(externalEventPartition)
                            .forEach(eventRecord -> {
                                handleExternalEvent((DomainEventRecord) eventRecord.value());
                                offsets.put(externalEventPartition, eventRecord.offset());
                            }));
            // then state (ignore?)
            allRecords.records(statePartition)
                    .forEach(stateRecord -> {
                        // commit the record to the repository
                        stateRepository.commit((AggregateStateRecord) stateRecord.value(), stateRecord.offset());
                        // commit the offset (TODO: not really necessary because we search for offset at startup)
                        offsets.put(statePartition, stateRecord.offset());
                    });
            // then internal events (ignore?)
            allRecords.records(domainEventPartition)
                    .forEach(domainEventRecord -> offsets.put(domainEventPartition, domainEventRecord.offset()));
            // find the processed offsets in each partition
            producer.sendOffsetsToTransaction(offsets.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue()+1))),
                    consumer.groupMetadata());
            producer.commitTransaction();
        } catch(InvalidProducerEpochException e) {
            // When encountering this exception, user should abort the ongoing transaction by calling
            // KafkaProducer#abortTransaction which would try to send initPidRequest and reinitialize the producer
            // under the hood
            producer.abortTransaction();
            rollbackConsumer(allRecords);
        }
    }

    private void rollbackConsumer(ConsumerRecords<String, ProtocolRecord> consumerRecords) {
        consumerRecords.partitions().forEach(topicPartition -> {
            // find the lowest offset
            consumerRecords.records(topicPartition).stream().map(ConsumerRecord::offset).min(Long::compareTo)
                    .ifPresent(offset -> consumer.seek(topicPartition, offset));
        });
    }
}
