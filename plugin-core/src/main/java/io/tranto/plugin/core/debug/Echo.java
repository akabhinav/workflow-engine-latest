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
 * Renders a dynamic {@code format} expression, logs it at {@code INFO} level and
 * returns the rendered value as output.
 *
 * <p>Mostly useful for debugging flows: it echoes a templated message back so the
 * evaluated value can be inspected in the logs or consumed by downstream tasks.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Echo a rendered message")
public class Echo extends Task implements RunnableTask<Echo.EchoOutput> {

    /**
     * The message template to render and echo. Supports dynamic expressions.
     */
    @PluginProperty(dynamic = true)
    private String format;

    @Override
    public EchoOutput run(final RunContext runContext) throws Exception {
        final String rendered = runContext.render(format);
        runContext.logger().info(rendered);

        return new EchoOutput(rendered);
    }

    /**
     * Output holding the rendered {@code format} value.
     *
     * @param value the rendered message that was echoed
     */
    public record EchoOutput(String value) implements Output {
    }
}
