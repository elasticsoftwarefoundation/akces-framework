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
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceDomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
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
        AggregateServiceRecord record = new AggregateServiceRecord(
                "Account",
                "Account-Commands",
                "Account-DomainEvents",
                List.of(new AggregateServiceCommandType("CreateAccount", 1, true, "commands.CreateAccount")),
                List.of(new AggregateServiceDomainEventType("AccountCreated",1, true, false, "domainevents.AccountCreated")),
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
        AkcesControlRecord deserialized = objectMapper.readValue(serializedRecord, AggregateServiceRecord.class);
        assertNotNull(deserialized);
        assertTrue(deserialized instanceof AggregateServiceRecord);
        assertEquals("Account", ((AggregateServiceRecord) deserialized).aggregateName());
        assertEquals("Account-Commands", ((AggregateServiceRecord) deserialized).commandTopic());
    }

    @Test
    public void testSerde() {
        AkcesControlRecordSerde serde = new AkcesControlRecordSerde(new ObjectMapper());
        AggregateServiceRecord record = new AggregateServiceRecord(
                "Account",
                "Account-Commands",
                "Account-DomainEvents",
                List.of(new AggregateServiceCommandType("CreateAccount", 1, true, "commands.CreateAccount")),
                List.of(new AggregateServiceDomainEventType("AccountCreated",1, true, false, "domainevents.AccountCreated")),
                Collections.emptyList());
        byte[] serialized = serde.serializer().serialize("Akces-Control", record);
        assertNotNull(serialized);

        AkcesControlRecord deserialized = serde.deserializer().deserialize("Akces-Control", serialized);
        assertNotNull(deserialized);
        assertTrue(deserialized instanceof AggregateServiceRecord);
        assertEquals("Account", ((AggregateServiceRecord) deserialized).aggregateName());
    }
}
