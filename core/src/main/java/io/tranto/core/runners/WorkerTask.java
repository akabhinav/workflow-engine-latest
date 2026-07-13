package io.tranto.core.runners;

import io.tranto.core.models.executions.TaskRun;
import io.tranto.core.models.tasks.Task;

import java.util.Map;

/**
 * A unit of work the executor hands to a worker: the task to run, the task-run being executed,
 * and the pre-assembled variable map for its {@link RunContext}.
 *
 * @param executionId the owning execution
 * @param task        the task definition to run
 * @param taskRun     the task-run to execute
 * @param variables   the variables for the run context
 */
public record WorkerTask(String executionId, Task task, TaskRun taskRun, Map<String, Object> variables) {
}
