package io.tranto.plugin.core.flow;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.VoidOutput;
import io.tranto.core.runners.RunContext;
import java.time.Duration;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Pauses execution for a configurable {@code duration}.
 *
 * <p>The duration is a rendered, ISO-8601 duration string (for example
 * {@code "PT2S"}). For safety the sleep is capped at {@value #MAX_SLEEP_MILLIS}
 * milliseconds. If the thread is interrupted while sleeping, the interrupt flag is
 * restored and the task returns promptly.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Sleep for a duration")
public class Sleep extends Task implements RunnableTask<VoidOutput> {

    /**
     * Maximum sleep, in milliseconds, enforced regardless of the requested duration.
     */
    private static final long MAX_SLEEP_MILLIS = 60_000L;

    /**
     * The ISO-8601 duration to sleep for (e.g. {@code "PT2S"}). Supports dynamic
     * expressions.
     */
    @PluginProperty(dynamic = true)
    private String duration;

    @Override
    public VoidOutput run(final RunContext runContext) throws Exception {
        final Duration parsed = Duration.parse(runContext.render(duration));
        final long millis = Math.min(parsed.toMillis(), MAX_SLEEP_MILLIS);

        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return new VoidOutput();
            }
        }

        return new VoidOutput();
    }
}
