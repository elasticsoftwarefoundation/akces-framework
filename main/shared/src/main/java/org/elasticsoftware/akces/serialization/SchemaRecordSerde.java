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

package org.elasticsoftware.akces.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.protobuf.ProtobufMapper;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.elasticsoftware.akces.protocol.SchemaRecord;

import java.io.IOException;
import java.io.StringReader;

public final class SchemaRecordSerde implements Serde<SchemaRecord> {
    private static final String schemaRecordProto = """
            // org.elasticsoftware.akces.protocol.SchemaRecord
            
            // Message for org.elasticsoftware.akces.protocol.SchemaRecord
            message SchemaRecord {
              optional string schemaName = 1;
              optional int32 version = 2;
              optional string schema = 3;
              optional int64 registeredAt = 4;
            }
            """;

    /**
     * Internal DTO for Protobuf serialization.
     * The schema field is String instead of JsonSchema to work with Protobuf serialization.
     */
    private record SchemaRecordDTO(
            String schemaName,
            int version,
            String schema,
            long registeredAt
    ) {
        /**
         * Converts a SchemaRecord to DTO (JsonSchema → String).
         */
        static SchemaRecordDTO from(SchemaRecord record, ObjectMapper jsonMapper) throws JsonProcessingException {
            return new SchemaRecordDTO(
                    record.schemaName(),
                    record.version(),
                    jsonMapper.writeValueAsString(record.schema()),
                    record.registeredAt()
            );
        }

        /**
         * Converts this DTO to SchemaRecord (String → JsonSchema).
         */
        SchemaRecord toSchemaRecord(ObjectMapper jsonMapper) throws JsonProcessingException {
            try {
                return new SchemaRecord(
                        schemaName,
                        version,
                        jsonMapper.readValue(schema, JsonSchema.class),
                        registeredAt
                );
            } catch (Exception e) {
                throw new SerializationException("Failed to convert DTO to SchemaRecord", e);
            }
        }
    }

    private final ObjectMapper protobufMapper = new ProtobufMapper();
    private final Serializer<SchemaRecord> serializer;
    private final Deserializer<SchemaRecord> deserializer;

    public SchemaRecordSerde(ObjectMapper jsonMapper) {
        try {
            ProtobufSchema schemaRecordSchema = ProtobufSchemaLoader.std.load(new StringReader(schemaRecordProto));
            serializer = new SerializerImpl(protobufMapper.writer(schemaRecordSchema), jsonMapper);
            deserializer = new DeserializerImpl(protobufMapper.readerFor(SchemaRecordDTO.class).with(schemaRecordSchema), jsonMapper);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public Serializer<SchemaRecord> serializer() {
        return serializer;
    }

    @Override
    public Deserializer<SchemaRecord> deserializer() {
        return deserializer;
    }

    private record SerializerImpl(ObjectWriter schemaRecordWriter, ObjectMapper jsonMapper) implements Serializer<SchemaRecord> {
        @Override
        public byte[] serialize(String topic, SchemaRecord data) {
            try {
                if (data == null) {
                    return null;
                }
                // Convert SchemaRecord to DTO (JsonSchema → String) before serialization
                SchemaRecordDTO dto = SchemaRecordDTO.from(data, jsonMapper);
                return schemaRecordWriter.writeValueAsBytes(dto);
            } catch (JsonProcessingException e) {
                throw new SerializationException(e);
            }
        }
    }

    private record DeserializerImpl(ObjectReader schemaRecordReader, ObjectMapper jsonMapper) implements Deserializer<SchemaRecord> {
        @Override
        public SchemaRecord deserialize(String topic, byte[] data) {
            try {
                if (data == null) {
                    return null;
                }
                // Deserialize to DTO then convert to SchemaRecord (String → JsonSchema)
                SchemaRecordDTO dto = schemaRecordReader.readValue(data);
                return dto.toSchemaRecord(jsonMapper);
            } catch (IOException e) {
                throw new SerializationException(e);
            }
        }
    }
}
