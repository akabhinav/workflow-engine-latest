package io.tranto.core.models.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * Container for repeatable {@link Example} annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(TYPE)
public @interface Examples {
    Example[] value();
}
