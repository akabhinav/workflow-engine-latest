package io.tranto.core.models.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;

/**
 * Describes a configurable property of a plugin. Drives JSON-schema generation for the
 * NoCode editor and controls runtime behaviour such as Pebble rendering and secret masking.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({FIELD, METHOD})
public @interface PluginProperty {

    /**
     * When {@code true}, the value is a Pebble expression rendered against the run context
     * before use.
     */
    boolean dynamic() default false;

    /** For map-typed properties, the class of the map values (schema hint). */
    Class<?> additionalProperties() default Object.class;

    /** Marks the property as beta. */
    boolean beta() default false;

    /** The value is a reference to an internal-storage ({@code kestra://}) URI. */
    boolean internalStorageURI() default false;

    /** Editor grouping key (properties with the same group render together). */
    String group() default "";

    /** Hide from the editor/catalogue (still settable in YAML). */
    boolean hidden() default false;

    /**
     * The value is a secret and must be masked in logs/UI. Secret properties must be
     * supplied as an expression, never a literal.
     */
    boolean secret() default false;
}
