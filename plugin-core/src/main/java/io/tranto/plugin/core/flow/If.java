package io.tranto.plugin.core.flow;

import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Runs the {@code then} tasks when {@code condition} renders truthy, otherwise the {@code else}
 * tasks. The condition is a Pebble expression evaluated at runtime.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Conditionally run tasks")
public class If extends Task implements FlowableTask<VoidOutput> {

    @PluginProperty(dynamic = true)
    private String condition;

    @JsonProperty("then")
    private List<Task> thenTasks;

    @JsonProperty("else")
    private List<Task> elseTasks;

    @Override
    public List<Task> activeChildren(final RunContext runContext) throws Exception {
        String rendered = runContext.render(condition);
        return isTruthy(rendered) ? nullSafe(thenTasks) : nullSafe(elseTasks);
    }

    @Override
    public List<Task> allChildren() {
        List<Task> all = new ArrayList<>(nullSafe(thenTasks));
        all.addAll(nullSafe(elseTasks));
        return all;
    }

    private static boolean isTruthy(final String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    private static List<Task> nullSafe(final List<Task> tasks) {
        return tasks == null ? List.of() : tasks;
    }
}
