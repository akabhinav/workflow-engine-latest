package io.tranto.plugin.core.log;

import io.tranto.core.models.annotations.Example;
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
 * Logs a message (Pebble-rendered) at the configured level. The canonical "hello world" task.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Log a message",
    examples = @Example(
        title = "Log a rendered greeting",
        code = {
            "id: hello",
            "type: io.tranto.plugin.core.log.Log",
            "message: \"Hello, {{ inputs.name }}!\""
        }
    )
)
public class Log extends Task implements RunnableTask<VoidOutput> {

    @PluginProperty(dynamic = true)
    private String message;

    @Override
    public VoidOutput run(final RunContext runContext) throws Exception {
        String rendered = runContext.render(message);
        runContext.logger().info(rendered);
        return new VoidOutput();
    }
}
