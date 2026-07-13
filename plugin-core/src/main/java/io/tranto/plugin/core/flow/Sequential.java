package io.tranto.plugin.core.flow;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.tasks.FlowableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.VoidOutput;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * Runs its child {@code tasks} one after another, stopping on the first failure. The explicit form
 * of the default top-level behaviour, useful for grouping inside a Parallel or a branch.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Run tasks sequentially")
public class Sequential extends Task implements FlowableTask<VoidOutput> {

    private List<Task> tasks;

    @Override
    public Mode mode() {
        return Mode.SEQUENTIAL;
    }

    @Override
    public List<Task> activeChildren(final RunContext runContext) {
        return tasks == null ? List.of() : tasks;
    }

    @Override
    public List<Task> allChildren() {
        return tasks == null ? List.of() : tasks;
    }
}
