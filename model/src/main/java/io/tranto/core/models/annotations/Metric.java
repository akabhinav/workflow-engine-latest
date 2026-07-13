package io.tranto.core.models.annotations;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * Declares a metric a plugin emits at runtime, so it can be documented and discovered.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(TYPE)
@Repeatable(Metrics.class)
public @interface Metric {

    /** Metric name (e.g. {@code records}). */
    String name();

    /** Metric type: {@code counter}, {@code timer}, or {@code gauge}. */
    String type();

    /** Unit of measure (e.g. {@code records}, {@code bytes}, {@code ms}); optional. */
    String unit() default "";

    /** Human-readable description; optional. */
    String description() default "";
}
