package io.tranto.core.runners;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.TaskRun;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.queues.QueueInterface;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs tasks. Consumes {@link WorkerTask}s, executes {@link RunnableTask#run(RunContext)} on a
 * virtual thread (via the queue's dispatcher), and returns a terminal {@link WorkerTaskResult}.
 * Never decides what runs next — that's the executor's job.
 */
public class Worker {

    private final RunContextFactory runContextFactory;
    private final QueueInterface<WorkerTaskResult> workerResultQueue;
    private final ExecutorService timeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public Worker(final RunContextFactory runContextFactory,
                  final QueueInterface<WorkerTaskResult> workerResultQueue) {
        this.runContextFactory = runContextFactory;
        this.workerResultQueue = workerResultQueue;
    }

    /** Subscribe to the worker-task queue. */
    public void start(final QueueInterface<WorkerTask> workerTaskQueue) {
        workerTaskQueue.receive(this::process);
    }

    void process(final WorkerTask workerTask) {
        RunContext runContext = runContextFactory.of(workerTask.variables());
        Task task = workerTask.task();
        TaskRun taskRun = workerTask.taskRun();

        // Skip conditions are evaluated before the task runs.
        try {
            if (task.isDisabled() || !shouldRun(task, runContext)) {
                workerResultQueue.emit(new WorkerTaskResult(taskRun.skipped()));
                return;
            }
        } catch (Exception e) {
            workerResultQueue.emit(new WorkerTaskResult(taskRun.failed(
                "Failed to evaluate runIf: " + e.getMessage())));
            return;
        }

        TaskRun running = taskRun.withState(StateType.RUNNING);
        try {
            Object outputs = task instanceof RunnableTask<?> runnable
                ? runWithTimeout(runnable, runContext, task.getTimeout())
                : null;
            workerResultQueue.emit(new WorkerTaskResult(running.success(outputs)));
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            if (task.isAllowFailure()) {
                runContext.logger().warn("Task '{}' failed but is allowed to: {}", task.getId(), message);
                workerResultQueue.emit(new WorkerTaskResult(running.warning(message)));
            } else {
                runContext.logger().error("Task '{}' failed: {}", task.getId(), message);
                workerResultQueue.emit(new WorkerTaskResult(running.failed(message)));
            }
        }
    }

    /** Run the task, enforcing {@code timeout} when set (on a virtual thread). */
    private Object runWithTimeout(final RunnableTask<?> runnable, final RunContext runContext,
                                  final Duration timeout) throws Exception {
        if (timeout == null) {
            return runnable.run(runContext);
        }
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return runnable.run(runContext);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, timeoutExecutor);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RuntimeException("Task timed out after " + timeout);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() instanceof CompletionException ce ? ce.getCause() : ee.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    /** @return true unless the task has a {@code runIf} that renders falsy. */
    private boolean shouldRun(final Task task, final RunContext runContext) throws Exception {
        String runIf = task.getRunIf();
        if (runIf == null || runIf.isBlank()) {
            return true;
        }
        String rendered = runContext.render(runIf).trim();
        return rendered.equalsIgnoreCase("true") || rendered.equals("1");
    }
}
