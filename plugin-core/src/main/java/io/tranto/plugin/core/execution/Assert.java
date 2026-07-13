package io.tranto.plugin.core.execution;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.VoidOutput;
import io.tranto.core.runners.RunContext;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Evaluates a list of boolean {@code conditions} and fails the task if any of
 * them does not render to {@code "true"} (case-insensitive).
 *
 * <p>Each condition is rendered individually. The first condition whose rendered
 * value is not {@code "true"} causes an {@link IllegalStateException} to be thrown,
 * referencing the original (un-rendered) condition expression. If every condition
 * holds, a {@link VoidOutput} is returned.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Assert that conditions hold")
public class Assert extends Task implements RunnableTask<VoidOutput> {

    /**
     * The conditions to evaluate. Each entry supports dynamic expressions and must
     * render to {@code "true"} (case-insensitive) for the assertion to pass.
     */
    @PluginProperty(dynamic = true)
    private List<String> conditions;

    @Override
    public VoidOutput run(final RunContext runContext) throws Exception {
        if (conditions != null) {
            for (final String condition : conditions) {
                final String rendered = runContext.render(condition);
                if (!"true".equalsIgnoreCase(rendered)) {
                    throw new IllegalStateException("Assertion failed: " + condition);
                }
            }
        }

        return new VoidOutput();
    }
}
