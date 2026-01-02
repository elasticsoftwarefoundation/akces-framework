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

package org.elasticsoftware.akces.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;

import java.io.IOException;

public final class AkcesControlRecordSerde implements Serde<AkcesControlRecord> {
    private final Serializer<AkcesControlRecord> serializer;
    private final Deserializer<AkcesControlRecord> deserializer;

    public AkcesControlRecordSerde(ObjectMapper objectMapper) {
        this.serializer = new SerializerImpl(objectMapper);
        this.deserializer = new DeserializerImpl(objectMapper);
    }

    @Override
    public Serializer<AkcesControlRecord> serializer() {
        return serializer;
    }

    @Override
    public Deserializer<AkcesControlRecord> deserializer() {
        return deserializer;
    }

    private record SerializerImpl(ObjectMapper objectMapper) implements Serializer<AkcesControlRecord> {

        @Override
        public byte[] serialize(String topic, AkcesControlRecord data) {
            try {
                if (data instanceof AggregateServiceRecord csr) {
                    return objectMapper.writeValueAsBytes(csr);
                } else {
                    throw new SerializationException("Unsupported AkcesControlRecord type " + data.getClass().getSimpleName());
                }
            } catch (JsonProcessingException e) {
                throw new SerializationException(e);
            }
        }
    }

    private record DeserializerImpl(ObjectMapper objectMapper) implements Deserializer<AkcesControlRecord> {

        @Override
        public AkcesControlRecord deserialize(String topic, byte[] data) {
            try {
                if (data == null) {
                    return null;
                } else if (topic.endsWith("Akces-Control")) {
                    return objectMapper.readValue(data, AkcesControlRecord.class);
                } else {
                    throw new SerializationException("Unsupported topic " + topic);
                }
            } catch (IOException e) {
                throw new SerializationException(e);
            }
        }
    }
}
