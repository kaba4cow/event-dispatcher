package com.kaba4cow.eventdispatcher;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods as event handlers.
 * <p>
 * Methods annotated with {@code EventHandler} must have a single parameter corresponding to the event type they handle.
 */
@Documented
@Retention(RUNTIME)
@Target(METHOD)
public @interface EventHandler {

}
