package io.tranto.core.models.triggers;

import io.tranto.core.models.Plugin;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * The abstract base of every flow trigger: the thing that <em>starts</em> an execution without a
 * human clicking "run" — a schedule (cron), a poll (a queue/file appears), a realtime event, or
 * another flow finishing.
 *
 * <p>Like {@code Task}, a trigger is a polymorphic plugin resolved by its {@code type}. Concrete
 * triggers additionally implement a capability interface — {@link Schedulable} for time-based ones,
 * (later) a polling interface for event-based ones.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public abstract class AbstractTrigger implements Plugin {

    /** Unique id of the trigger within its flow. */
    @NotBlank
    protected String id;

    /** The plugin type identifier (e.g. {@code io.tranto.plugin.core.trigger.Schedule}). */
    @NotBlank
    protected String type;

    /** Optional human description. */
    protected String description;

    /** When true the trigger is inactive (never fires). */
    protected boolean disabled;
}
