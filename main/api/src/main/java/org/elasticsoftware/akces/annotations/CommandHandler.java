package org.elasticsoftware.akces.annotations;

import io.micronaut.aop.Adapter;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Adapter(CommandHandlerFunction.class)
public @interface CommandHandler {
    boolean create() default false;
}
