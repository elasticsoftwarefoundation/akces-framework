package org.elasticsoftware.akces.annotations;

public @interface AggregateStateInfo {
    String type();

    int version() default 1;
}
