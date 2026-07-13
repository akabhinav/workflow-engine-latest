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
import java.util.Map;

/**
 * Renders {@code value} and runs the matching entry in {@code cases}; if none matches, runs
 * {@code defaults}. A multi-way branch.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Switch on a rendered value")
public class Switch extends Task implements FlowableTask<VoidOutput> {

    @PluginProperty(dynamic = true)
    private String value;

    private Map<String, List<Task>> cases;

    private List<Task> defaults;

    @Override
    public List<Task> activeChildren(final RunContext runContext) throws Exception {
        String key = runContext.render(value);
        List<Task> matched = cases == null ? null : cases.get(key);
        return matched != null ? matched : nullSafe(defaults);
    }

    @Override
    public List<Task> allChildren() {
        List<Task> all = new ArrayList<>();
        if (cases != null) {
            cases.values().forEach(all::addAll);
        }
        all.addAll(nullSafe(defaults));
        return all;
    }

    private static List<Task> nullSafe(final List<Task> tasks) {
        return tasks == null ? List.of() : tasks;
    }
}
