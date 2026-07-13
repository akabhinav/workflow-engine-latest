package io.tranto.plugin.core.execution;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.VoidOutput;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A graceful end marker.
 *
 * <p>When present, an optional {@code message} is rendered and logged at
 * {@code INFO} level before the task returns. This task performs no other side
 * effect; it simply signals a clean stopping point in a flow.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Gracefully exit")
public class Exit extends Task implements RunnableTask<VoidOutput> {

    /**
     * Optional message to render and log when exiting. Supports dynamic expressions.
     */
    @PluginProperty(dynamic = true)
    private String message;

    @Override
    public VoidOutput run(final RunContext runContext) throws Exception {
        if (message != null) {
            runContext.logger().info(runContext.render(message));
        }

        return new VoidOutput();
    }
}
