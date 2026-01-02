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

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.std.StdValueInstantiator;
import org.elasticsoftware.akces.annotations.PIIData;

import java.util.Iterator;

public class PIIDataDeserializerModifier extends BeanDeserializerModifier {
    private final PIIDataJsonDeserializer instance = new PIIDataJsonDeserializer();

    @Override
    public BeanDeserializerBuilder updateBuilder(DeserializationConfig config,
                                                 BeanDescription beanDescription,
                                                 BeanDeserializerBuilder builder) {
        Iterator<SettableBeanProperty> it = builder.getProperties();

        while (it.hasNext()) {
            SettableBeanProperty p = it.next();
            if (p.getAnnotation(PIIData.class) != null) {
                builder.addOrReplaceProperty(p.withValueDeserializer(instance), true);
            }
        }

        if (builder.getValueInstantiator() != null) {
            SettableBeanProperty[] constructorArguments = builder.getValueInstantiator().getFromObjectArguments(config);
            if (constructorArguments != null) {
                SettableBeanProperty[] updatedArguments = new SettableBeanProperty[constructorArguments.length];
                for (int i = 0; i < constructorArguments.length; i++) {
                    if (constructorArguments[i].getAnnotation(PIIData.class) != null) {
                        updatedArguments[i] = constructorArguments[i].withValueDeserializer(instance);
                    } else {
                        updatedArguments[i] = constructorArguments[i];
                    }
                }
                builder.setValueInstantiator(new PersonalDataValueInstantiator((StdValueInstantiator) builder.getValueInstantiator(), updatedArguments));
            }
        }


        return builder;
    }

    static class PersonalDataValueInstantiator extends StdValueInstantiator {

        PersonalDataValueInstantiator(StdValueInstantiator src, SettableBeanProperty[] arguments) {
            super(src);
            _constructorArguments = arguments;
        }
    }

}
