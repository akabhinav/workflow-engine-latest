package io.tranto.core.runners;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.executions.TaskRun;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.tasks.ExecutableTask;
import io.tranto.core.queues.QueueInterface;
import io.tranto.core.repositories.ExecutionRepository;
import io.tranto.core.repositories.FlowRepository;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The flow state machine. Consumes new executions and worker results, decides the next task-run,
 * dispatches work, and drives the execution to a terminal state.
 *
 * <p>Phase 3 implements sequential flows (one task after another). Phase 4 adds flowable control
 * flow (If/Parallel/Loop/Subflow), retries, pauses and concurrency. A {@link LockProvider} serialises
 * processing of a single execution while different executions run in parallel — the correctness
 * primitive that makes at-least-once delivery safe. In distributed mode the lock and the
 * {@link ConcurrencyStore} are cluster-wide (JDBC-backed), so the invariants hold across nodes.</p>
 */
public class Executor {

    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final QueueInterface<WorkerTask> workerTaskQueue;
    private final QueueInterface<Execution> executionUpdatedQueue;
    private final RunContextFactory runContextFactory;

    /** Serialises processing of a single execution; per-JVM by default, cluster-wide in distributed mode. */
    private final LockProvider lockProvider;

    /** Per-flow concurrency slots; per-JVM by default, cluster-wide (JDBC) in distributed mode. */
    private final ConcurrencyStore concurrencyStore;

    /** The execution queue (stored so subflow children can be submitted). */
    private QueueInterface<Execution> executionQueue;

    /** Shared daemon scheduler for delayed retries. */
    private static final ScheduledExecutorService RETRY_SCHEDULER =
        Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "tranto-retry");
            t.setDaemon(true);
            return t;
        });

    /** Per-JVM locking + slot accounting (standalone engine). */
    public Executor(final FlowRepository flowRepository,
                    final ExecutionRepository executionRepository,
                    final QueueInterface<WorkerTask> workerTaskQueue,
                    final QueueInterface<Execution> executionUpdatedQueue,
                    final RunContextFactory runContextFactory) {
        this(flowRepository, executionRepository, workerTaskQueue, executionUpdatedQueue,
            runContextFactory, new LockProvider.InMemory(), new ConcurrencyStore.InMemory());
    }

    /**
     * @param lockProvider     serialises per-execution processing; pass a {@link JdbcLockProvider} to
     *                         serialise across executor nodes in distributed mode
     * @param concurrencyStore tracks per-flow concurrency slots; pass a
     *                         {@link io.tranto.core.jdbc.JdbcConcurrencyStore} for cluster-wide limits
     */
    public Executor(final FlowRepository flowRepository,
                    final ExecutionRepository executionRepository,
                    final QueueInterface<WorkerTask> workerTaskQueue,
                    final QueueInterface<Execution> executionUpdatedQueue,
                    final RunContextFactory runContextFactory,
                    final LockProvider lockProvider,
                    final ConcurrencyStore concurrencyStore) {
        this.flowRepository = flowRepository;
        this.executionRepository = executionRepository;
        this.workerTaskQueue = workerTaskQueue;
        this.executionUpdatedQueue = executionUpdatedQueue;
        this.runContextFactory = runContextFactory;
        this.lockProvider = lockProvider;
        this.concurrencyStore = concurrencyStore;
    }

    /** Subscribe to the execution and worker-result queues. */
    public void start(final QueueInterface<Execution> executionQueue,
                      final QueueInterface<WorkerTaskResult> workerResultQueue) {
        this.executionQueue = executionQueue;
        executionQueue.receive(this::onExecution);
        workerResultQueue.receive(this::onWorkerResult);
    }

    /** A brand-new execution arrived: gate it on concurrency, then persist and start driving it. */
    void onExecution(final Execution execution) {
        if (!admit(execution)) {
            return; // queued for later, or rejected (cancelled/failed) inside admit()
        }
        withLock(execution.getId(), () -> {
            executionRepository.save(execution);
            process(execution);
        });
    }

    /**
     * Concurrency gate: if the flow has a limit and it is reached, either hold the execution QUEUED
     * (admitting it later when a slot frees) or terminate it immediately per the flow's behaviour.
     *
     * @return true if the execution may proceed now (and has taken a slot when the flow is limited)
     */
    private boolean admit(final Execution execution) {
        Flow flow = flowRepository
            .findById(execution.getTenantId(), execution.getNamespace(),
                execution.getFlowId(), execution.getFlowRevision())
            .orElse(null);
        var concurrency = flow == null ? null : flow.getConcurrency();
        if (concurrency == null || concurrency.limit() <= 0) {
            return true;
        }
        String key = flowKey(execution);
        if (concurrencyStore.tryAdmit(key, execution.getId(), concurrency.limit())) {
            return true;
        }
        switch (concurrency.behavior()) {
            case QUEUE -> {
                Execution queued = execution.withState(StateType.QUEUED);
                executionRepository.save(queued);
                concurrencyStore.enqueue(key, execution.getId());
                executionUpdatedQueue.emit(queued);
            }
            case CANCEL -> terminateImmediately(execution, StateType.CANCELLED);
            case FAIL -> terminateImmediately(execution, StateType.FAILED);
            default -> { }
        }
        return false;
    }

    /** Persist and emit an over-limit execution straight to a terminal state (no dispatch). */
    private void terminateImmediately(final Execution execution, final StateType terminal) {
        Execution done = execution.withState(terminal);
        executionRepository.save(done);
        executionUpdatedQueue.emit(done);
    }

    /** Release the concurrency slot an execution held (if any) and admit the next queued one. */
    private void releaseSlot(final Execution ended) {
        String promotedId = concurrencyStore.releaseAndPromote(flowKey(ended), ended.getId());
        if (promotedId == null) {
            return;
        }
        Execution next = executionRepository.findById(promotedId).orElse(null);
        if (next == null) {
            return;
        }
        Execution started = next.withState(StateType.RUNNING);
        withLock(started.getId(), () -> {
            executionRepository.save(started);
            process(started);
        });
    }

    private static String flowKey(final Execution execution) {
        return execution.getTenantId() + "|" + execution.getNamespace() + "|" + execution.getFlowId();
    }

    /** A worker finished a task: retry if the policy allows, otherwise join the result and continue. */
    void onWorkerResult(final WorkerTaskResult result) {
        String executionId = result.taskRun().getExecutionId();
        withLock(executionId, () -> executionRepository.findById(executionId).ifPresent(execution -> {
            if (execution.getState().isTerminated()) {
                return; // execution already killed/finished — drop the late result
            }
            if (tryRetry(execution, result)) {
                return; // re-dispatched a new attempt; do not join the failure yet
            }
            process(execution.withTaskRun(result.taskRun()));
        }));
    }

    /** Kill an execution: mark all non-terminal task-runs KILLED and end it KILLED. */
    public void kill(final String executionId) {
        withLock(executionId, () -> executionRepository.findById(executionId).ifPresent(execution -> {
            if (execution.getState().isTerminated()) {
                return;
            }
            var killedRuns = execution.getTaskRunList().stream()
                .map(r -> r.isTerminated() ? r : r.withState(StateType.KILLED))
                .toList();
            Execution killed = execution.toBuilder().taskRunList(killedRuns).build()
                .withState(StateType.KILLED);
            executionRepository.save(killed);
            executionUpdatedQueue.emit(killed);
            releaseSlot(killed);
        }));
    }

    /** Resume a PAUSED execution: complete its paused task-run(s) and continue the flow. */
    public void resume(final String executionId) {
        withLock(executionId, () -> executionRepository.findById(executionId).ifPresent(execution -> {
            if (execution.getState().current() != StateType.PAUSED) {
                return;
            }
            var resumedRuns = execution.getTaskRunList().stream()
                .map(r -> r.getState().current() == StateType.PAUSED ? r.withState(StateType.SUCCESS) : r)
                .toList();
            Execution resumed = execution.toBuilder().taskRunList(resumedRuns).build()
                .withState(StateType.RUNNING);
            process(resumed);
        }));
    }

    /**
     * If the task-run failed and its task has retry attempts remaining, re-dispatch a fresh attempt
     * and return true; otherwise return false so the failure is joined normally.
     */
    private boolean tryRetry(final Execution execution, final WorkerTaskResult result) {
        io.tranto.core.models.executions.TaskRun taskRun = result.taskRun();
        if (taskRun.getState().current() != StateType.FAILED) {
            return false;
        }
        Flow flow = flowRepository
            .findById(execution.getTenantId(), execution.getNamespace(),
                execution.getFlowId(), execution.getFlowRevision())
            .orElse(null);
        if (flow == null) {
            return false;
        }
        var task = flow.findTask(taskRun.getTaskId()).orElse(null);
        io.tranto.core.models.tasks.RetryPolicy policy = task == null ? null : task.getRetry();
        int maxAttempts = policy != null ? policy.maxAttempts() : 0;
        if (policy == null || taskRun.getAttempts() >= maxAttempts) {
            return false;
        }

        // Honour the overall retry time budget: stop once we've been retrying longer than maxDuration.
        if (policy.maxDuration() != null && taskRun.getState().startDate() != null) {
            Duration elapsed = Duration.between(taskRun.getState().startDate(), java.time.Instant.now());
            if (elapsed.compareTo(policy.maxDuration()) >= 0) {
                return false;
            }
        }

        int nextAttempt = taskRun.getAttempts() + 1;
        var retryRun = taskRun.toBuilder()
            .attempts(nextAttempt)
            .build()
            .withState(StateType.SUBMITTED);
        Execution updated = execution.withTaskRun(retryRun);
        executionRepository.save(updated);
        executionUpdatedQueue.emit(updated);

        WorkerTask workerTask = new WorkerTask(
            updated.getId(), task, retryRun, variablesForRun(flow, updated, retryRun));
        Duration delay = policy.delayForAttempt(nextAttempt);
        if (delay != null && !delay.isZero() && !delay.isNegative()) {
            RETRY_SCHEDULER.schedule(() -> workerTaskQueue.emit(workerTask), delay.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            workerTaskQueue.emit(workerTask);
        }
        return true;
    }

    /** Base variables plus this task-run's own info ({@code taskrun.value}/{@code taskrun.id}). */
    private Map<String, Object> variablesForRun(final Flow flow, final Execution execution,
                                                final io.tranto.core.models.executions.TaskRun taskRun) {
        Map<String, Object> variables = runContextFactory.baseVariables(flow, execution);
        Map<String, Object> taskRunInfo = new java.util.LinkedHashMap<>();
        taskRunInfo.put("id", taskRun.getId());
        taskRunInfo.put("value", taskRun.getValue());
        variables.put("taskrun", taskRunInfo);
        return variables;
    }

    /**
     * The core step: run the planner over the current execution state, then either dispatch the
     * next leaf task-runs, end the execution at its terminal state, or persist and wait.
     */
    private void process(final Execution execution) {
        Flow flow = flowRepository
            .findById(execution.getTenantId(), execution.getNamespace(),
                execution.getFlowId(), execution.getFlowRevision())
            .orElseThrow(() -> new IllegalStateException(
                "Flow not found for execution " + execution.getId()));

        ExecutionPlanner.Resolution resolution =
            new ExecutionPlanner(runContextFactory, flow).plan(execution);
        Execution planned = resolution.execution();

        if (resolution.isPaused()) {
            Execution paused = planned.withState(StateType.PAUSED);
            executionRepository.save(paused);
            executionUpdatedQueue.emit(paused);
        } else if (resolution.hasNexts()) {
            Execution running = planned.getState().current() == StateType.RUNNING
                ? planned
                : planned.withState(StateType.RUNNING);
            executionRepository.save(running);
            executionUpdatedQueue.emit(running);

            resolution.nexts().forEach(next -> {
                if (next.task() instanceof ExecutableTask executable) {
                    spawnSubflow(running, next.taskRun(), executable);
                } else {
                    workerTaskQueue.emit(new WorkerTask(
                        running.getId(), next.task(), next.taskRun(),
                        variablesForRun(flow, running, next.taskRun())));
                }
            });
        } else if (resolution.isTerminal()) {
            end(flow, planned, resolution.terminal());
        } else {
            // Work in flight — persist any newly-opened flowable runs and wait.
            executionRepository.save(planned);
        }
    }

    private void end(final Flow flow, final Execution execution, final StateType terminal) {
        // Roll a tolerated failure (WARNING) up to the execution when it otherwise succeeded.
        StateType finalState = terminal;
        if (terminal == StateType.SUCCESS && execution.getTaskRunList() != null
            && execution.getTaskRunList().stream()
                .anyMatch(r -> r.getState().current() == StateType.WARNING)) {
            finalState = StateType.WARNING;
        }
        Execution ended = execution.withState(finalState);
        ended = renderFlowOutputs(flow, ended);
        executionRepository.save(ended);
        executionUpdatedQueue.emit(ended);

        // A concurrency-limited flow: free the slot and admit the next queued execution.
        releaseSlot(ended);

        // If this was a subflow child, map its outcome back onto the waiting parent task-run.
        if (ended.getParentExecutionId() != null) {
            joinSubflow(ended.getParentExecutionId(), ended.getParentTaskRunId(), finalState);
        }
    }

    /** Render the flow's declared outputs against the terminal execution and attach them. */
    private Execution renderFlowOutputs(final Flow flow, final Execution ended) {
        if (flow.getOutputs() == null || flow.getOutputs().isEmpty()) {
            return ended;
        }
        RunContext runContext = runContextFactory.of(runContextFactory.baseVariables(flow, ended));
        Map<String, Object> outputs = new java.util.LinkedHashMap<>();
        for (var output : flow.getOutputs()) {
            try {
                outputs.put(output.id(), runContext.render(output.value()));
            } catch (Exception e) {
                outputs.put(output.id(), null);
            }
        }
        return ended.toBuilder().outputs(outputs).build();
    }

    /**
     * Submit a child execution for a Subflow task. The parent reference is stored <em>on the child
     * execution</em> (persisted), not in an in-JVM map, so whichever executor node ends the child can
     * join it back onto the parent — the subflow-join half of the distributed-state fix.
     */
    private void spawnSubflow(final Execution parent, final TaskRun parentTaskRun, final ExecutableTask executable) {
        Execution child = Execution.newExecution(
                executable.subflowNamespace(), executable.subflowId(), null, Map.of(), null)
            .toBuilder()
            .parentExecutionId(parent.getId())
            .parentTaskRunId(parentTaskRun.getId())
            .build();
        executionRepository.save(child);
        executionQueue.emit(child);
    }

    /** Complete a parent's subflow task-run with the child's terminal state, then continue the parent. */
    private void joinSubflow(final String parentExecutionId, final String parentTaskRunId,
                             final StateType childState) {
        withLock(parentExecutionId, () ->
            executionRepository.findById(parentExecutionId).ifPresent(parentExecution -> {
                if (parentExecution.getState().isTerminated()) {
                    return;
                }
                TaskRun parentRun = parentExecution.getTaskRunList().stream()
                    .filter(r -> r.getId().equals(parentTaskRunId))
                    .findFirst().orElse(null);
                if (parentRun == null || parentRun.isTerminated()) {
                    return;
                }
                process(parentExecution.withTaskRun(parentRun.withState(childState)));
            }));
    }

    private void withLock(final String executionId, final Runnable action) {
        lockProvider.withLock(executionId, action);
    }
}
