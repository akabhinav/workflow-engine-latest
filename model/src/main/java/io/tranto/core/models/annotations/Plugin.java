package io.tranto.core.models.annotations;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;

/**
 * Marks a class as a Tranto plugin (a Task, Trigger, Condition, TaskRunner, Storage, ...)
 * and carries the metadata used to document and render it.
 *
 * <p>The class must also implement the {@code Plugin} marker interface (defined in the
 * {@code core} module). At build time the annotation processor turns every concrete
 * {@code @Plugin} class into a {@code ServiceLoader} entry so it is discoverable at runtime
 * without reflection scanning.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({TYPE, ANNOTATION_TYPE})
@Inherited
public @interface Plugin {

    /** Optional display title; defaults to the class's simple name when blank. */
    String title() default "";

    /** Usage examples rendered in the docs/UI. */
    Example[] examples() default {};

    /** Metrics this plugin emits. */
    Metric[] metrics() default {};

    /** Functional categories this plugin belongs to. */
    PluginCategory[] categories() default {};

    /** Marks the plugin as beta (subject to change). */
    boolean beta() default false;

    /**
     * Internal plugins are resolvable by the engine but not offered to users in the
     * editor/catalogue.
     */
    boolean internal() default false;

    /**
     * Alternate {@code type:} identifiers this plugin also answers to (e.g. after a
     * rename), so existing flows keep working. Matched case-insensitively.
     */
    String[] aliases() default {};

    /**
     * A stable, human-friendly identifier for the plugin, independent of its class name.
     * Optional; when absent the fully-qualified class name is the identifier.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(TYPE)
    @Inherited
    @interface Id {
        String value();
    }
}
