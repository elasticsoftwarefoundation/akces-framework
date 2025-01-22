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

package org.elasticsoftware.akces.gdpr.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import org.elasticsoftware.akces.annotations.PIIData;

import java.util.ArrayList;
import java.util.List;

public class PIIDataSerializerModifier extends BeanSerializerModifier {
    private final PIIDataJsonSerializer instance = new PIIDataJsonSerializer();

    @Override
    public List<BeanPropertyWriter> changeProperties(final SerializationConfig config,
                                                     final BeanDescription beanDescription,
                                                     final List<BeanPropertyWriter> beanProperties) {
        List<BeanPropertyWriter> newWriters = new ArrayList<>();

        for (final BeanPropertyWriter writer : beanProperties) {
            if (null == writer.getAnnotation(PIIData.class)) {
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
