package org.elasticsoftware.akces.annotations;

import jakarta.inject.Named;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Named
public @interface AggregateInfo {
    String value();

    int version() default 1;
}
