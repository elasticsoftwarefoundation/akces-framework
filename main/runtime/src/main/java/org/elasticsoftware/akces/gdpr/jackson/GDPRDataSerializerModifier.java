package org.elasticsoftware.akces.gdpr.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import org.elasticsoftware.akces.annotations.GDPRData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class GDPRDataSerializerModifier extends BeanSerializerModifier {
    private static final Logger logger = LoggerFactory.getLogger(GDPRDataSerializerModifier.class);
    private final GDPRDataJsonSerializer instance = new GDPRDataJsonSerializer();

    @Override
    public List<BeanPropertyWriter> changeProperties(final SerializationConfig config,
                                                     final BeanDescription beanDescription,
                                                     final List<BeanPropertyWriter> beanProperties) {
        List<BeanPropertyWriter> newWriters = new ArrayList<>();

        for (final BeanPropertyWriter writer : beanProperties) {
            if (null == writer.getAnnotation(GDPRData.class)) {
                newWriters.add(writer);
            } else {
                newWriters.add(new PersonalDataPropertyWriter(writer, instance));
            }
        }
        return newWriters;
    }

    static class PersonalDataPropertyWriter extends BeanPropertyWriter {
        PersonalDataPropertyWriter(final BeanPropertyWriter base, final JsonSerializer<Object> serializer) {
            super(base);
            this._serializer = serializer;
        }
    }
}
