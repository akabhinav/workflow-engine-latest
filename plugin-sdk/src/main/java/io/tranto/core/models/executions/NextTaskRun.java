package io.tranto.core.models.executions;

import io.tranto.core.models.tasks.Task;

/**
 * A task the executor has decided to start next: the task definition paired with its freshly
 * created task-run. Runnable next-runs are dispatched to a worker; flowable ones are expanded
 * further by the executor.
 *
 * @param task    the task to run
 * @param taskRun its new task-run (SUBMITTED)
 */
public record NextTaskRun(Task task, TaskRun taskRun) {
}
