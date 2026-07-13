package io.tranto.core.models.tasks;

import io.tranto.core.models.Plugin;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

/**
 * The abstract base of every task in a flow. Concrete tasks are plugins that additionally
 * implement a capability interface:
 * <ul>
 *   <li>{@link RunnableTask} — does work; runs on a <em>worker</em>.</li>
 *   <li>{@code FlowableTask} — controls flow (If/Parallel/Loop); runs on the <em>executor</em>.</li>
 *   <li>{@code ExecutableTask} — spawns subflow executions.</li>
 * </ul>
 *
 * <p>Mutable Lombok POJO (matching plugin-author expectations): built with {@code @SuperBuilder}
 * and deserialized field-by-field from YAML by the plugin registry.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public abstract class Task implements Plugin {

    /** Unique id of the task within its flow. */
    @NotBlank
    protected String id;

    /** The plugin type identifier (e.g. {@code io.tranto.plugin.core.log.Log}). */
    @NotBlank
    protected String type;

    /** Optional human description. */
    protected String description;

    /** When true the task is skipped (state {@code SKIPPED}). */
    protected boolean disabled;

    /** Optional hard timeout for the task's execution. */
    protected Duration timeout;

    /**
     * Optional Pebble condition; when it renders falsy the task is skipped.
     */
    protected String runIf;

    /** Treat a failure as a success (state {@code WARNING} instead of {@code FAILED}). */
    protected boolean allowFailure;

    /** Treat a warning as a success (state {@code SUCCESS} instead of {@code WARNING}). */
    protected boolean allowWarning;

    /** Optional retry policy; when set, a failed run is re-dispatched up to its attempt limit. */
    protected RetryPolicy retry;

    /** @return true if this task orchestrates other tasks (handled by the executor). */
    public boolean isFlowable() {
        return this instanceof FlowableTask;
    }

    /** @return true if this task does work and must be sent to a worker. */
    public boolean isRunnable() {
        return this instanceof RunnableTask;
    }
}
