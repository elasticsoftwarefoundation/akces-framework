package org.elasticsoftware.akces.annotations;

import io.micronaut.core.annotation.Introspected;

@Introspected
public @interface DomainEventInfo {
    String type();

    int version() default 1;
}
