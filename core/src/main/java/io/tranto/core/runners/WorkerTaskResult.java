package io.tranto.core.runners;

import io.tranto.core.models.executions.TaskRun;

/**
 * The worker's reply to a {@link WorkerTask}: the task-run in its terminal state (SUCCESS with
 * outputs, or FAILED with an error). The executor joins this back into the execution.
 *
 * @param taskRun the completed task-run
 */
public record WorkerTaskResult(TaskRun taskRun) {
}
