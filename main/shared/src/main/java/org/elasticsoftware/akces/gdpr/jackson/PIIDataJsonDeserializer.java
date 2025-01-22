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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;

import java.io.IOException;

public class PIIDataJsonDeserializer extends StringDeserializer {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String encryptedString = super.deserialize(p, ctxt);
        GDPRContext gdprContext = GDPRContextHolder.getCurrentGDPRContext();
        return gdprContext != null ? gdprContext.decrypt(encryptedString) : encryptedString;
    }
}
