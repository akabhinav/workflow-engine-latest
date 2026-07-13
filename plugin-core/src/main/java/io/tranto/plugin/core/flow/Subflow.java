package io.tranto.plugin.core.flow;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.tasks.ExecutableTask;
import io.tranto.core.models.tasks.Task;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Runs another flow as a child execution and waits for it. The parent task-run mirrors the child
 * execution's terminal state (SUCCESS/WARNING/FAILED).
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Run another flow (subflow)")
public class Subflow extends Task implements ExecutableTask {

    private String namespace;

    private String flowId;

    @Override
    public String subflowNamespace() {
        return namespace;
    }

    @Override
    public String subflowId() {
        return flowId;
    }
}
