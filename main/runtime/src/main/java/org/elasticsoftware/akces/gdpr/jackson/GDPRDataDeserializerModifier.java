package org.elasticsoftware.akces.gdpr.jackson;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.std.StdValueInstantiator;
import org.elasticsoftware.akces.annotations.GDPRData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

public class GDPRDataDeserializerModifier extends BeanDeserializerModifier {
    private static final Logger logger = LoggerFactory.getLogger(GDPRDataDeserializerModifier.class);
    private final GDPRDataJsonDeserializer instance = new GDPRDataJsonDeserializer();

    @Override
    public BeanDeserializerBuilder updateBuilder(DeserializationConfig config,
                                                 BeanDescription beanDescription,
                                                 BeanDeserializerBuilder builder) {
        Iterator<SettableBeanProperty> it = builder.getProperties();

        while (it.hasNext()) {
            SettableBeanProperty p = it.next();
            if (p.getAnnotation(GDPRData.class) != null) {
                builder.addOrReplaceProperty(p.withValueDeserializer(instance), true);
            }
        }

        if (builder.getValueInstantiator() != null) {
            SettableBeanProperty[] constructorArguments = builder.getValueInstantiator().getFromObjectArguments(config);
            if (constructorArguments != null) {
                SettableBeanProperty[] updatedArguments = new SettableBeanProperty[constructorArguments.length];
                for (int i = 0; i < constructorArguments.length; i++) {
                    if (constructorArguments[i].getAnnotation(GDPRData.class) != null) {
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
