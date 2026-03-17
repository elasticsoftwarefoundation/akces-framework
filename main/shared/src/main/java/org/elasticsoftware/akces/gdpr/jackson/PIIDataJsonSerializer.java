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

package org.elasticsoftware.akces.gdpr.jackson;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;

public class PIIDataJsonSerializer extends ValueSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext provider) throws JacksonException {
        GDPRContext gdprContext = GDPRContextHolder.getCurrentGDPRContext();
        if (gdprContext != null) {
            gen.writeString(gdprContext.encrypt((String) value));
        } else {
            gen.writeString((String) value);
        }
    }

    @Override
    public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint) {
        visitor.expectStringFormat(typeHint);
    }
}
