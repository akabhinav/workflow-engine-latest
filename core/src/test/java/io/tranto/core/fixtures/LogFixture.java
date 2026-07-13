package io.tranto.core.fixtures;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.VoidOutput;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A minimal built-in-style task used by tests to prove the plugin SPI end-to-end: it is a
 * concrete {@link RunnableTask} resolved from YAML by the registry, and its {@link #run} simply
 * logs a (rendered) message. Mirrors what a real {@code io.tranto.plugin.core.log.Log} will be.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Log a message")
public class LogFixture extends Task implements RunnableTask<VoidOutput> {

    /** The message to log (Pebble-renderable in later phases). */
    private String message;

    @Override
    public VoidOutput run(final RunContext runContext) {
        runContext.logger().info(message);
        return new VoidOutput();
    }
}
