package io.tranto.plugin.core.flow;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.FlowableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.VoidOutput;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs its child {@code tasks} once per value in {@code values}. Each iteration's tasks can read the
 * current value via {@code {{ taskrun.value }}}. Iterations run one-at-a-time by default, or all at
 * once when {@code concurrent: true}.
 *
 * <p>{@code values} is rendered then parsed as a list: a JSON-ish {@code [a, b, c]} or a
 * comma/newline-separated string.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Loop over a list of values (ForEach)")
public class Loop extends Task implements FlowableTask<VoidOutput> {

    @PluginProperty(dynamic = true)
    private String values;

    private List<Task> tasks;

    /** When true, run all iterations concurrently instead of one after another. */
    private boolean concurrent;

    @Override
    public Mode mode() {
        return concurrent ? Mode.PARALLEL : Mode.SEQUENTIAL;
    }

    @Override
    public List<Task> activeChildren(final RunContext runContext) {
        return tasks == null ? List.of() : tasks;
    }

    @Override
    public List<Task> allChildren() {
        return tasks == null ? List.of() : tasks;
    }

    @Override
    public List<String> iterationValues(final RunContext runContext) throws Exception {
        return parse(runContext.render(values));
    }

    /** Parse a rendered value list: strips [] brackets and splits on comma/newline, trimming quotes. */
    private static List<String> parse(final String rendered) {
        if (rendered == null || rendered.isBlank()) {
            return List.of();
        }
        String body = rendered.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : body.split("[,\\n]")) {
            String value = part.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return out;
    }
}
