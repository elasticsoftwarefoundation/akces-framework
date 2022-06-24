package org.elasticsoftware.akces.protocol;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.protobuf.ProtobufMapper;
import com.fasterxml.jackson.dataformat.protobuf.schema.NativeProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.elasticsoftware.akces.protocol.PayloadEncoding.JSON;
import static org.testng.Assert.assertEquals;

public class ProtocolTests {
    @Test
    public void testJacksonProtobuf() throws IOException {
        String protobuf = """
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
                }
                // Enum for org.elasticsoftware.akces.protocol.PayloadEncoding
                enum PayloadEncoding {
                  JSON = 0;
                  PROTOBUF = 1;
                  AVRO = 2;
                }
                """;
        DomainEventRecord testRecord = new DomainEventRecord("WalletCreated", 1, "{}".getBytes(StandardCharsets.UTF_8), JSON, "1", UUID.randomUUID().toString(), 1L);
        /*
        InputStream ioStream = this.getClass()
                .getClassLoader()
                .getResourceAsStream("protobuf/DomainEventRecord.proto");
         */
        ProtobufSchema schema = ProtobufSchemaLoader.std.load(new StringReader(protobuf));
        ObjectMapper objectMapper = new ProtobufMapper();
        byte[] serializedTestRecord = objectMapper.writer(schema).writeValueAsBytes(testRecord);

        DomainEventRecord deserializedTestRecord = objectMapper.readerFor(DomainEventRecord.class).with(schema).readValue(serializedTestRecord);

        assertEquals(deserializedTestRecord.name(), testRecord.name());
        assertEquals(deserializedTestRecord.version(), testRecord.version());
        assertEquals(deserializedTestRecord.payload(), testRecord.payload());
        assertEquals(deserializedTestRecord.encoding(), testRecord.encoding());
        assertEquals(deserializedTestRecord.aggregateId(), testRecord.aggregateId());
    }

    @Test
    public void generateDomainEventRecordProtobufSchema() throws JsonMappingException {
        ProtobufMapper mapper = new ProtobufMapper();
        ProtobufSchema schemaWrapper = mapper.generateSchemaFor(DomainEventRecord.class);
        NativeProtobufSchema nativeProtobufSchema = schemaWrapper.getSource();

        String asProtofile = nativeProtobufSchema.toString();

        System.out.println(asProtofile);
    }

    @Test
    public void generateCommandRecordProtobufSchema() throws JsonMappingException {
        ProtobufMapper mapper = new ProtobufMapper();
        ProtobufSchema schemaWrapper = mapper.generateSchemaFor(CommandRecord.class);
        NativeProtobufSchema nativeProtobufSchema = schemaWrapper.getSource();

        String asProtofile = nativeProtobufSchema.toString();

        System.out.println(asProtofile);
    }

    @Test
    public void generateAggregateStateRecordProtobufSchema() throws JsonMappingException {
        ProtobufMapper mapper = new ProtobufMapper();
        ProtobufSchema schemaWrapper = mapper.generateSchemaFor(AggregateStateRecord.class);
        NativeProtobufSchema nativeProtobufSchema = schemaWrapper.getSource();

        String asProtofile = nativeProtobufSchema.toString();

        System.out.println(asProtofile);
    }
}
