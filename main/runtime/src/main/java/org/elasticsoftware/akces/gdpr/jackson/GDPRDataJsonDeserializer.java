package org.elasticsoftware.akces.gdpr.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;

import java.io.IOException;

public class GDPRDataJsonDeserializer extends StringDeserializer {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String encryptedString = super.deserialize(p, ctxt);
        GDPRContext gdprContext = GDPRContextHolder.getCurrentGDPRContext();
        return gdprContext != null ? gdprContext.decrypt(encryptedString) : encryptedString;
    }
}
