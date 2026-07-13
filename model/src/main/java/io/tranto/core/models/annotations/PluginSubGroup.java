package io.tranto.core.models.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PACKAGE;

/**
 * Package-level grouping metadata for a family of plugins (e.g. all tasks under one
 * integration). Read from {@code package-info.java} to organise the plugin catalogue.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(PACKAGE)
public @interface PluginSubGroup {

    /** Display title for the sub-group. */
    String title() default "";

    /** Description of what this group of plugins does. */
    String description() default "";

    /** Categories shared by the plugins in this group. */
    PluginCategory[] categories() default {};
}
