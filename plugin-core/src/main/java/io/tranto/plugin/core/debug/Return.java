package io.tranto.plugin.core.debug;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Renders {@code format} and returns it as this task's {@code value} output, for debugging and
 * for feeding downstream tasks via {@code {{ outputs.<id>.value }}}.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Return a rendered value as output")
public class Return extends Task implements RunnableTask<Return.ReturnOutput> {

    @PluginProperty(dynamic = true)
    private String format;

    @Override
    public ReturnOutput run(final RunContext runContext) throws Exception {
        String value = runContext.render(format);
        runContext.logger().debug("Return produced: {}", value);
        return new ReturnOutput(value);
    }

    /** The rendered value. */
    public record ReturnOutput(String value) implements Output {
    }
}
