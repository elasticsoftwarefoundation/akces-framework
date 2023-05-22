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

package org.elasticsoftware.akcestest.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.control.CommandServiceCommandType;
import org.elasticsoftware.akces.control.CommandServiceDomainEventType;
import org.elasticsoftware.akces.control.CommandServiceRecord;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


public class AkcesControllerTests {
    @Test
    public void testSerialization() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        CommandServiceRecord record = new CommandServiceRecord(
                "Account",
                "Account-Commands",
                "Account-DomainEvents",
                List.of(new CommandServiceCommandType<>("CreateAccount", 1, true)),
                List.of(new CommandServiceDomainEventType<>("AccountCreated",1, true, false)),
                Collections.emptyList());
        assertNotNull(record);
        //objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, record);
    }

    @Test
    public void testDeserialization() throws JsonProcessingException {
        String serializedRecord = """
                {
                  "aggregateName" : "Account",
                  "commandTopic" : "Account-Commands",
                  "supportedCommands" : [ {
                    "typeName" : "CreateAccount",
                    "version" : 1,
                    "create" : true
                  } ],
                  "producedEvents" : [ {
                    "typeName" : "AccountCreated",
                    "version" : 1,
                    "create" : true,
                    "external" : false
                  } ],
                  "consumedEvents" : [ ]
                }
                """;

        ObjectMapper objectMapper = new ObjectMapper();
        AkcesControlRecord deserialized = objectMapper.readValue(serializedRecord, CommandServiceRecord.class);
        assertNotNull(deserialized);
        assertTrue(deserialized instanceof CommandServiceRecord);
        assertEquals("Account", ((CommandServiceRecord) deserialized).aggregateName());
        assertEquals("Account-Commands", ((CommandServiceRecord) deserialized).commandTopic());
    }

    @Test
    public void testSerde() {
        AkcesControlRecordSerde serde = new AkcesControlRecordSerde(new ObjectMapper());
        CommandServiceRecord record = new CommandServiceRecord(
                "Account",
                "Account-Commands",
                "Account-DomainEvents",
                List.of(new CommandServiceCommandType<>("CreateAccount", 1, true)),
                List.of(new CommandServiceDomainEventType<>("AccountCreated",1, true, false)),
                Collections.emptyList());
        byte[] serialized = serde.serializer().serialize("Akces-Control", record);
        assertNotNull(serialized);

        AkcesControlRecord deserialized = serde.deserializer().deserialize("Akces-Control", serialized);
        assertNotNull(deserialized);
        assertTrue(deserialized instanceof CommandServiceRecord);
        assertEquals("Account", ((CommandServiceRecord) deserialized).aggregateName());
    }
}
