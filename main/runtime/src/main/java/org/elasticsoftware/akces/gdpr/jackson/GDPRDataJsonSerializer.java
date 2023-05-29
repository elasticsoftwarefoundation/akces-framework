package org.elasticsoftware.akces.gdpr.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;

import java.io.IOException;

public class GDPRDataJsonSerializer extends JsonSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        GDPRContext gdprContext = GDPRContextHolder.getCurrentGDPRContext();
        if (gdprContext != null) {
            gen.writeString(gdprContext.encrypt((String) value));
        } else {
            gen.writeString((String) value);
        }
    }

    @Override
    public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint) throws JsonMappingException {
        visitor.expectStringFormat(typeHint);
    }
}
