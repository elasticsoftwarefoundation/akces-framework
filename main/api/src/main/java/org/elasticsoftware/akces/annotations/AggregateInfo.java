package org.elasticsoftware.akces.annotations;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Component
public @interface AggregateInfo {
    @AliasFor(annotation = Component.class)
    String value();

    int version() default 1;
}
