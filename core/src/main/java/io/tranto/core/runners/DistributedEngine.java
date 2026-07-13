package io.tranto.core.runners;

import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.jdbc.JdbcLock;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.queues.JdbcQueue;
import io.tranto.core.repositories.ExecutionRepository;
import io.tranto.core.repositories.FlowRepository;
import io.tranto.core.repositories.jdbc.JdbcExecutionRepository;
import io.tranto.core.repositories.jdbc.JdbcFlowRepository;
import io.tranto.core.schedulers.FlowTriggerEvaluator;
import io.tranto.core.schedulers.Scheduler;
import io.tranto.core.serializers.JacksonMapper;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The distributed runtime: the SAME {@link Executor}/{@link Worker}/{@link Scheduler} as the
 * standalone engine, but wired onto JDBC-backed queues and repositories instead of in-memory ones.
 * This is what proves the platform's central design claim — services talk only through
 * {@code QueueInterface}/repository interfaces, so swapping the transport changes nothing in the
 * executor or worker.
 *
 * <p>Roles can be split: run one instance as an <em>executor</em> and another as a <em>worker</em>,
 * both pointed at the same {@link JdbcDatabase}, and they coordinate entirely through the shared
 * queue tables — the shape of a real multi-node deployment. An all-roles instance is the
 * JDBC-backed equivalent of {@code server local}.</p>
 *
 * <p>Note: concurrency admission and KV/storage are still per-instance here; a fully distributed
 * deployment moves those to shared JDBC state stores (see STATUS.md). The queue/executor/worker
 * coordination — the hard part — is real.</p>
 */
public class DistributedEngine implements AutoCloseable {

    /** Scheduler leader lease TTL — longer than the 1s tick so the holder renews before it expires. */
    private static final long SCHEDULER_LEASE_MS = 5_000;

    private final JdbcQueue<Execution> executionQueue;
    private final JdbcQueue<WorkerTask> workerTaskQueue;
    private final JdbcQueue<WorkerTaskResult> workerResultQueue;
    private final JdbcQueue<Execution> executionUpdatedQueue;

    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final RunContextFactory runContextFactory;
    private final InputResolver inputResolver = new InputResolver();

    private final ConcurrentHashMap<String, CompletableFuture<Execution>> waiters = new ConcurrentHashMap<>();
    private final Executor executor;
    private final Scheduler scheduler;
    private final FlowTriggerEvaluator flowTriggers;
    private final boolean schedulerRole;

    /** All-roles instance (executor + worker + scheduler) — the JDBC-backed {@code server local}. */
    public DistributedEngine(final JdbcDatabase database, final JacksonMapper mappers) {
        this(database, mappers, true, true, true);
    }

    /**
     * @param database        the shared JDBC database (queues + repositories live here)
     * @param mappers         the registry-aware Jackson mappers (for polymorphic task serde)
     * @param executorRole    run the executor (consume executions + worker results, drive the state machine)
     * @param workerRole      run the worker (consume worker tasks, execute them)
     * @param schedulerRole   run the scheduler (fire time-based triggers)
     */
    public DistributedEngine(final JdbcDatabase database, final JacksonMapper mappers,
                             final boolean executorRole, final boolean workerRole, final boolean schedulerRole) {
        var json = mappers.json();
        this.executionQueue = new JdbcQueue<>(database, "execution", Execution.class, json);
        this.workerTaskQueue = new JdbcQueue<>(database, "worker-task", WorkerTask.class, json);
        this.workerResultQueue = new JdbcQueue<>(database, "worker-result", WorkerTaskResult.class, json);
        this.executionUpdatedQueue = new JdbcQueue<>(database, "execution-updated", Execution.class, json);

        this.flowRepository = new JdbcFlowRepository(database, mappers);
        this.executionRepository = new JdbcExecutionRepository(database, mappers);

        // Shared KV + object storage so kv.Set/Get and stored files are visible on every node.
        this.runContextFactory = new RunContextFactory(
            new VariableRenderer(),
            new io.tranto.core.storages.JdbcStorage(database),
            new io.tranto.core.kv.JdbcKVStore.Factory(database, json));

        // This node's identity, used as the holder for cluster-wide locks/leases.
        String nodeId = java.util.UUID.randomUUID().toString();
        JdbcLock jdbcLock = new JdbcLock(database);

        // Cluster-wide per-execution lock + cluster-wide concurrency slots: two executor nodes cannot
        // process one execution concurrently, and a limit-N flow admits at most N across the cluster.
        this.executor = new Executor(
            flowRepository, executionRepository, workerTaskQueue, executionUpdatedQueue, runContextFactory,
            new JdbcLockProvider(jdbcLock, nodeId),
            new io.tranto.core.jdbc.JdbcConcurrencyStore(database, jdbcLock, nodeId));
        // Scheduler fires only while this node holds the leader lease (exactly-one cron fire per window).
        this.scheduler = new Scheduler(
            (flow, trigger) -> submit(flow, Map.of()),
            () -> jdbcLock.tryAcquire("scheduler-leader", nodeId, SCHEDULER_LEASE_MS));
        this.flowTriggers = new FlowTriggerEvaluator(flowRepository, flow -> submit(flow, Map.of()));
        this.schedulerRole = schedulerRole;

        if (executorRole) {
            executor.start(executionQueue, workerResultQueue);
            executionUpdatedQueue.receive(this::onExecutionUpdated);
        }
        if (workerRole) {
            new Worker(runContextFactory, workerResultQueue).start(workerTaskQueue);
        }
        if (schedulerRole) {
            scheduler.start();
        }
    }

    /** Run a flow to completion (blocking) — only meaningful on an instance with the executor role. */
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
            executionRepository.save(execution);
            executionQueue.emit(execution);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Execution " + execution.getId() + " did not terminate in time", e);
        } finally {
            waiters.remove(execution.getId());
        }
    }

    /** Submit a flow without blocking; poll {@link #executions()} to observe progress. */
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

    private Execution failFast(final Flow flow, final String reason) {
        Execution failed = Execution.newExecution(
                flow.getNamespace(), flow.getId(), flow.getRevision(), Map.of(), flow.getLabels())
            .withState(io.tranto.core.models.execution.StateType.FAILED)
            .toBuilder().outputs(Map.of("error", reason)).build();
        executionRepository.save(failed);
        return failed;
    }

    /** Register (persist) a flow and arm its triggers. */
    public Flow register(final Flow flow) {
        Flow saved = flowRepository.save(flow);
        if (schedulerRole) {
            scheduler.register(saved, ZonedDateTime.now());
        }
        return saved;
    }

    public void kill(final String executionId) {
        executor.kill(executionId);
    }

    public void resume(final String executionId) {
        executor.resume(executionId);
    }

    public FlowRepository flows() {
        return flowRepository;
    }

    public ExecutionRepository executions() {
        return executionRepository;
    }

    private void onExecutionUpdated(final Execution execution) {
        if (execution.getState().isTerminated()) {
            CompletableFuture<Execution> future = waiters.get(execution.getId());
            if (future != null) {
                future.complete(execution);
            }
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
