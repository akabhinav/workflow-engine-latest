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
 * Runs all of its child {@code tasks} concurrently. The flowable completes when every child has
 * terminated; it fails if any child fails.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Run tasks in parallel")
public class Parallel extends Task implements FlowableTask<VoidOutput> {

    private List<Task> tasks;

    @Override
    public Mode mode() {
        return Mode.PARALLEL;
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
