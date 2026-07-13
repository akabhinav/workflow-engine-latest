package io.tranto.core.models.tasks;

import io.tranto.core.models.Plugin;

/**
 * A task that spawns and awaits a child execution of another flow (a Subflow). Handled by the
 * executor, not a worker: it submits a child execution and, when that child terminates, maps the
 * child's outcome onto this task's run.
 */
public interface ExecutableTask extends Plugin {

    /** @return the namespace of the flow to execute. */
    String subflowNamespace();

    /** @return the id of the flow to execute. */
    String subflowId();
}
