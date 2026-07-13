package io.tranto.core.runners;

import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.queues.MemoryQueue;
import io.tranto.core.repositories.ExecutionRepository;
import io.tranto.core.repositories.FlowRepository;
import io.tranto.core.repositories.memory.MemoryExecutionRepository;
import io.tranto.core.repositories.memory.MemoryFlowRepository;
import io.tranto.core.schedulers.FlowTriggerEvaluator;
import io.tranto.core.schedulers.Scheduler;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * All-in-one, in-process orchestrator: wires the in-memory queues, repositories, executor and
 * worker together so a flow can be run end-to-end in a single JVM. This is the engine behind
 * {@code server local} and the fastest way to prove the architecture.
 *
 * <p>The distributed deployment swaps the in-memory queues/repositories for JDBC ones behind the
 * same interfaces; none of the executor/worker code changes.</p>
 */
public class StandaloneEngine implements AutoCloseable {

    private final MemoryQueue<Execution> executionQueue = new MemoryQueue<>();
    private final MemoryQueue<WorkerTask> workerTaskQueue = new MemoryQueue<>();
    private final MemoryQueue<WorkerTaskResult> workerResultQueue = new MemoryQueue<>();
    private final MemoryQueue<Execution> executionUpdatedQueue = new MemoryQueue<>();

    private final FlowRepository flowRepository = new MemoryFlowRepository();
    private final ExecutionRepository executionRepository = new MemoryExecutionRepository();
    private final RunContextFactory runContextFactory = new RunContextFactory();

    private final ConcurrentHashMap<String, CompletableFuture<Execution>> waiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> executionsByState =
        new ConcurrentHashMap<>();
    private final InputResolver inputResolver = new InputResolver();
    private final Executor executor;
    private final Scheduler scheduler;
    private final FlowTriggerEvaluator flowTriggers;

    public StandaloneEngine() {
        this.executor = new Executor(
            flowRepository, executionRepository, workerTaskQueue, executionUpdatedQueue, runContextFactory);
        Worker worker = new Worker(runContextFactory, workerResultQueue);
        this.scheduler = new Scheduler((flow, trigger) -> submit(flow, Map.of()));
        this.flowTriggers = new FlowTriggerEvaluator(flowRepository, flow -> submit(flow, Map.of()));

        executor.start(executionQueue, workerResultQueue);
        worker.start(workerTaskQueue);
        executionUpdatedQueue.receive(this::onExecutionUpdated);
        scheduler.start();
    }

    /** @return the scheduler driving this engine's time-based triggers (exposed for tests/tools). */
    public Scheduler scheduler() {
        return scheduler;
    }

    /** Kill a running execution. */
    public void kill(final String executionId) {
        executor.kill(executionId);
    }

    /** Resume a paused execution. */
    public void resume(final String executionId) {
        executor.resume(executionId);
    }

    /**
     * Run a flow to completion (blocking).
     *
     * @param flow    the flow to run
     * @param inputs  input values (may be null/empty)
     * @param timeout maximum time to wait for a terminal state
     * @return the terminal execution
     */
    public Execution run(final Flow flow, final Map<String, Object> inputs, final Duration timeout) {
        register(flow);

        Map<String, Object> resolvedInputs;
        try {
            resolvedInputs = inputResolver.resolve(flow.getInputs(), inputs);
        } catch (InputResolver.InputValidationException e) {
            return failFast(flow, e.getMessage());
        }

        Execution execution = Execution.newExecution(
            flow.getNamespace(), flow.getId(), flow.getRevision(), resolvedInputs, flow.getLabels());

        CompletableFuture<Execution> future = new CompletableFuture<>();
        waiters.put(execution.getId(), future);
        try {
            executionQueue.emit(execution);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Execution " + execution.getId() + " did not terminate in time", e);
        } finally {
            waiters.remove(execution.getId());
        }
    }

    /**
     * Submit a flow for execution without waiting. Returns the freshly-created execution; poll
     * {@link #executions()} by id to observe its progress. Used by the REST API.
     */
    public Execution submit(final Flow flow, final Map<String, Object> inputs) {
        register(flow);

        Map<String, Object> resolvedInputs;
        try {
            resolvedInputs = inputResolver.resolve(flow.getInputs(), inputs);
        } catch (InputResolver.InputValidationException e) {
            return failFast(flow, e.getMessage());
        }

        Execution execution = Execution.newExecution(
            flow.getNamespace(), flow.getId(), flow.getRevision(), resolvedInputs, flow.getLabels());
        executionRepository.save(execution);
        executionQueue.emit(execution);
        return execution;
    }

    /** Build and persist a FAILED execution (never dispatched) for an invalid-input rejection. */
    private Execution failFast(final Flow flow, final String reason) {
        Execution failed = Execution.newExecution(
                flow.getNamespace(), flow.getId(), flow.getRevision(), Map.of(), flow.getLabels())
            .withState(io.tranto.core.models.execution.StateType.FAILED)
            .toBuilder().outputs(Map.of("error", reason)).build();
        executionRepository.save(failed);
        return failed;
    }

    /** Register a flow so it can later be executed by namespace/id, and arm its triggers. */
    public Flow register(final Flow flow) {
        Flow saved = flowRepository.save(flow);
        scheduler.register(saved, ZonedDateTime.now());
        return saved;
    }

    /** @return the flow repository (for the API to look up registered flows). */
    public FlowRepository flows() {
        return flowRepository;
    }

    /** @return the execution repository (for inspection/tests/API polling). */
    public ExecutionRepository executions() {
        return executionRepository;
    }

    /**
     * Subscribe to every execution state change (create/running/terminal). Used by the SSE "follow"
     * endpoint to stream live updates; filter by execution id in the listener.
     *
     * @param listener invoked on each execution update
     * @return a handle that unsubscribes when closed
     */
    public AutoCloseable subscribe(final java.util.function.Consumer<Execution> listener) {
        return executionUpdatedQueue.receive(listener);
    }

    /**
     * @return a snapshot of execution counts by terminal state (e.g. {@code {SUCCESS=12, FAILED=1}}),
     *         plus {@code total}. A lightweight built-in metric; a fuller Micrometer/OTel export is a
     *         later step.
     */
    public Map<String, Long> metrics() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        long total = 0;
        for (var entry : executionsByState.entrySet()) {
            long value = entry.getValue().get();
            snapshot.put(entry.getKey(), value);
            total += value;
        }
        snapshot.put("total", total);
        return snapshot;
    }

    private void onExecutionUpdated(final Execution execution) {
        if (execution.getState().isTerminated()) {
            CompletableFuture<Execution> future = waiters.get(execution.getId());
            if (future != null) {
                future.complete(execution);
            }
            executionsByState
                .computeIfAbsent(execution.getState().current().name(),
                    k -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
            flowTriggers.onTerminal(execution);
        }
    }

    @Override
    public void close() {
        scheduler.close();
        executionQueue.close();
        workerTaskQueue.close();
        workerResultQueue.close();
        executionUpdatedQueue.close();
    }
}
