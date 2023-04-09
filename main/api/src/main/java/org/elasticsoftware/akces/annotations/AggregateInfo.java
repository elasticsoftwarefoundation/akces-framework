package org.elasticsoftware.akces.annotations;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Factory
@Qualifier
public @interface AggregateInfo {
    String value();
}
