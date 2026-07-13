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
 * Fails the execution on purpose with a (rendered) message. Useful for testing error handling and
 * for asserting conditions in a flow.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Fail the execution")
public class Fail extends Task implements RunnableTask<VoidOutput> {

    @PluginProperty(dynamic = true)
    private String message;

    @Override
    public VoidOutput run(final RunContext runContext) throws Exception {
        String rendered = message == null ? "Task failed on purpose" : runContext.render(message);
        throw new IllegalStateException(rendered);
    }
}
