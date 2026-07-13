package io.tranto.core.models.annotations;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

/**
 * A usage example for a plugin, surfaced in the generated documentation and the UI.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(TYPE)
@Repeatable(Examples.class)
public @interface Example {

    /** Short title describing what the example demonstrates. */
    String title() default "";

    /** The example body, one entry per line. */
    String[] code();

    /** Whether {@link #code()} is a full flow ({@code true}) or a task fragment. */
    boolean full() default false;

    /** Language of the snippet (for editor syntax highlighting). */
    String lang() default "yaml";
}
