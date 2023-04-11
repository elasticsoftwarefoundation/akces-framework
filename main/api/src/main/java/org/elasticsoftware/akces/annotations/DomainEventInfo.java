package org.elasticsoftware.akces.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface DomainEventInfo {
    String type();

    int version() default 1;
}
