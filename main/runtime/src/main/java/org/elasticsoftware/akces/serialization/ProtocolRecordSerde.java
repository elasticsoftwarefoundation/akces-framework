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

package org.elasticsoftware.akces.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.protobuf.ProtobufMapper;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

public final class ProtocolRecordSerde implements Serde<ProtocolRecord> {
    private static final String domainEventRecordProto = """
        // org.elasticsoftware.akces.protocol.DomainEventRecord
                        
        // Message for org.elasticsoftware.akces.protocol.DomainEventRecord
        message DomainEventRecord {
          optional string name = 1;
          optional int32 version = 2;
          optional bytes payload = 3;
          optional PayloadEncoding encoding = 4;
          optional string aggregateId = 5;
          optional string correlationId = 6;
          optional int64 generation = 7;
          optional string tenantId = 8;
        }
        // Enum for org.elasticsoftware.akces.protocol.PayloadEncoding
        enum PayloadEncoding {
          JSON = 0;
          PROTOBUF = 1;
        }
        """;
    private static final String aggregateStateRecordProto = """
        // org.elasticsoftware.akces.protocol.AggregateStateRecord
                    
        // Message for org.elasticsoftware.akces.protocol.AggregateStateRecord
        message AggregateStateRecord {
          optional string name = 1;
          optional int32 version = 2;
          optional bytes payload = 3;
          optional PayloadEncoding encoding = 4;
          optional string aggregateId = 5;
          optional string correlationId = 6;
          optional int64 generation = 7;
          optional string tenantId = 8;
        }
        // Enum for org.elasticsoftware.akces.protocol.PayloadEncoding
        enum PayloadEncoding {
          JSON = 0;
          PROTOBUF = 1;
        }
        """;
    private static final String commandRecordProto = """
        // org.elasticsoftware.akces.protocol.CommandRecord
                    
        // Message for org.elasticsoftware.akces.protocol.CommandRecord
        message CommandRecord {
          optional string name = 1;
          optional int32 version = 2;
          optional bytes payload = 3;
          optional PayloadEncoding encoding = 4;
          optional string aggregateId = 5;
          optional string correlationId = 6;
          optional string tenantId = 7;
        }
        // Enum for org.elasticsoftware.akces.protocol.PayloadEncoding
        enum PayloadEncoding {
          JSON = 0;
          PROTOBUF = 1;
        }
        """;
    private final ObjectMapper objectMapper = new ProtobufMapper();
    private final Serializer<ProtocolRecord> serializer;
    private final Deserializer<ProtocolRecord> deserializer;

    public ProtocolRecordSerde() {
        try {
            ProtobufSchema domainEventRecordSchema = ProtobufSchemaLoader.std.load(new StringReader(domainEventRecordProto));
            ProtobufSchema aggregateStateRecordSchema = ProtobufSchemaLoader.std.load(new StringReader(aggregateStateRecordProto));
            ProtobufSchema commandRecordSchema = ProtobufSchemaLoader.std.load(new StringReader(commandRecordProto));
            serializer = new SerializerImpl(objectMapper.writer(domainEventRecordSchema),
                    objectMapper.writer(aggregateStateRecordSchema),
                    objectMapper.writer(commandRecordSchema));
            deserializer = new DeserializerImpl(objectMapper.readerFor(DomainEventRecord.class).with(domainEventRecordSchema),
                    objectMapper.readerFor(AggregateStateRecord.class).with(aggregateStateRecordSchema),
                    objectMapper.readerFor(CommandRecord.class).with(commandRecordSchema));
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {

    }

    @Override
    public void close() {

    }

    @Override
    public Serializer<ProtocolRecord> serializer() {
        return serializer;
    }

    @Override
    public Deserializer<ProtocolRecord> deserializer() {
        return deserializer;
    }

    private static class SerializerImpl implements Serializer<ProtocolRecord> {
        private final ObjectWriter domainEventRecordWriter;
        private final ObjectWriter aggregateStateRecordWriter;
        private final ObjectWriter commandRecordWriter;

        private SerializerImpl(ObjectWriter domainEventRecordWriter,
                               ObjectWriter aggregateStateRecordWriter,
                               ObjectWriter commandRecordWriter) {
            this.domainEventRecordWriter = domainEventRecordWriter;
            this.aggregateStateRecordWriter = aggregateStateRecordWriter;
            this.commandRecordWriter = commandRecordWriter;
        }


        @Override
        public byte[] serialize(String topic, ProtocolRecord data) {
            try {
                if (data instanceof DomainEventRecord r) {
                    return domainEventRecordWriter.writeValueAsBytes(r);
                } else if(data instanceof AggregateStateRecord r) {
                    return aggregateStateRecordWriter.writeValueAsBytes(r);
                } else if(data instanceof CommandRecord r) {
                    return commandRecordWriter.writeValueAsBytes(r);
                } else {
                    throw new SerializationException("Unsupported ProtocolRecord type " + data.getClass().getSimpleName());
                }
            } catch(JsonProcessingException e) {
                throw new SerializationException(e);
            }
        }
    }

    private static class DeserializerImpl implements Deserializer<ProtocolRecord> {
        private final ObjectReader domainEventRecordReader;
        private final ObjectReader aggregateStateRecordReader;
        private final ObjectReader commandRecordReader;

        public DeserializerImpl(ObjectReader domainEventRecordReader,
                                ObjectReader aggregateStateRecordReader,
                                ObjectReader commandRecordReader) {
            this.domainEventRecordReader = domainEventRecordReader;
            this.aggregateStateRecordReader = aggregateStateRecordReader;
            this.commandRecordReader = commandRecordReader;
        }

        @Override
        public ProtocolRecord deserialize(String topic, byte[] data) {
            try {
                if (data == null) {
                    return null;
                } else if (topic.endsWith("DomainEvents")) {
                    return domainEventRecordReader.readValue(data);
                } else if (topic.endsWith("AggregateState")) {
                    return aggregateStateRecordReader.readValue(data);
                } else if(topic.endsWith("Commands")) {
                    return commandRecordReader.readValue(data);
                } else {
                    throw new SerializationException("Unsupported topic name " + topic + " cannot determine ProtocolRecordType");
                }
            } catch(IOException e) {
                throw new SerializationException(e);
            }
        }
    }
}
