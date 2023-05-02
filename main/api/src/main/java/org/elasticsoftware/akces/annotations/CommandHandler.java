package org.elasticsoftware.akces.annotations;

import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandHandler {
    boolean create() default false;

    Class<? extends DomainEvent> produces();

    Class<? extends ErrorEvent>[] errors();
}
