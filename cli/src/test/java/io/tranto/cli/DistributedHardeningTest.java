package io.tranto.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.jdbc.JdbcConcurrencyStore;
import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.jdbc.JdbcLock;
import io.tranto.core.kv.JdbcKVStore;
import io.tranto.core.kv.KVStore;
import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.ConcurrencyStore;
import io.tranto.core.runners.DistributedEngine;
import io.tranto.core.runners.JdbcLockProvider;
import io.tranto.core.runners.LockProvider;
import io.tranto.core.schedulers.Scheduler;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.core.storages.JdbcStorage;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributed-hardening guarantees, tested at the seam:
 * <ul>
 *   <li><b>Landmine #2</b> — two executor <em>nodes</em> (distinct {@link JdbcLockProvider} holders)
 *       sharing one database serialise read→mutate→write on the same execution: no lost update.</li>
 *   <li><b>Landmine #4</b> — two scheduler nodes gated on the same leader lease fire a due cron
 *       exactly once, not once per node.</li>
 * </ul>
 */
class DistributedHardeningTest {

    private Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    @Test
    void twoExecutorNodesSerialiseTheSameExecution_noLostUpdate() throws Exception {
        JdbcDatabase shared = JdbcDatabase.h2InMemory("hard_exec_lock");
        JdbcLock lock = new JdbcLock(shared);

        // Two independent "nodes": distinct holder ids, distinct in-JVM locks — only the shared DB
        // lock can serialise them.
        LockProvider nodeA = new JdbcLockProvider(lock, "node-A");
        LockProvider nodeB = new JdbcLockProvider(lock, "node-B");

        int[] shared_state = {0}; // a non-atomic counter standing in for the execution's JSON blob
        int threadsPerNode = 3;
        int itersPerThread = 25;
        int expected = 2 * threadsPerNode * itersPerThread;

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadsPerNode; i++) {
            threads.add(worker(nodeA, shared_state, itersPerThread));
            threads.add(worker(nodeB, shared_state, itersPerThread));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        assertThat(shared_state[0])
            .as("cross-node lock must serialise the read-modify-write (no lost update)")
            .isEqualTo(expected);
    }

    private Thread worker(final LockProvider provider, final int[] state, final int iters) {
        return new Thread(() -> {
            for (int i = 0; i < iters; i++) {
                provider.withLock("execution-1", () -> {
                    int current = state[0];
                    // Widen the read-modify-write window so an unsynchronised race would lose updates.
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    state[0] = current + 1;
                });
            }
        });
    }

    @Test
    void twoSchedulerNodesFireDueCronExactlyOnce() throws Exception {
        JdbcDatabase shared = JdbcDatabase.h2InMemory("hard_sched_leader");
        JdbcLock lock = new JdbcLock(shared);

        Flow flow = parse("""
            id: cron_flow
            namespace: dev
            triggers:
              - id: minutely
                type: io.tranto.plugin.core.trigger.Schedule
                cron: "* * * * *"
                timezone: UTC
            tasks:
              - id: hello
                type: io.tranto.plugin.core.log.Log
                message: "tick"
            """);

        AtomicInteger fires = new AtomicInteger();
        // Both nodes gate on the same "scheduler-leader" lease; only the holder fires.
        Scheduler nodeA = new Scheduler((f, t) -> fires.incrementAndGet(),
            () -> lock.tryAcquire("scheduler-leader", "node-A", 5_000));
        Scheduler nodeB = new Scheduler((f, t) -> fires.incrementAndGet(),
            () -> lock.tryAcquire("scheduler-leader", "node-B", 5_000));

        try (nodeA; nodeB) {
            ZonedDateTime t0 = ZonedDateTime.of(2026, 7, 5, 10, 0, 30, 0, ZoneOffset.UTC);
            nodeA.register(flow, t0);
            nodeB.register(flow, t0);

            // A due window: both nodes tick, but the cron must fire exactly once across the cluster.
            ZonedDateTime due = ZonedDateTime.of(2026, 7, 5, 10, 1, 5, 0, ZoneOffset.UTC);
            nodeA.tick(due);
            nodeB.tick(due);
            assertThat(fires.get()).as("exactly one node fires the due cron").isEqualTo(1);

            // The following window: still exactly one more fire (both advanced their clocks).
            ZonedDateTime next = ZonedDateTime.of(2026, 7, 5, 10, 2, 5, 0, ZoneOffset.UTC);
            nodeA.tick(next);
            nodeB.tick(next);
            assertThat(fires.get()).isEqualTo(2);
        }
    }

    @Test
    void kvWrittenOnOneNodeIsVisibleOnAnother() {
        JdbcDatabase shared = JdbcDatabase.h2InMemory("hard_kv");
        ObjectMapper mapper = new ObjectMapper();
        // Two nodes = two independent factories over the same database.
        KVStore nodeA = new JdbcKVStore.Factory(shared, mapper).forNamespace("dev");
        KVStore nodeB = new JdbcKVStore.Factory(shared, mapper).forNamespace("dev");

        nodeA.put("cursor", "page-7");
        assertThat(nodeB.get("cursor")).contains("page-7");

        // Namespace isolation: a different namespace on node B does not see it.
        KVStore otherNs = new JdbcKVStore.Factory(shared, mapper).forNamespace("prod");
        assertThat(otherNs.get("cursor")).isEmpty();
    }

    @Test
    void storageWrittenOnOneNodeIsReadableOnAnother() throws Exception {
        JdbcDatabase shared = JdbcDatabase.h2InMemory("hard_storage");
        JdbcStorage nodeA = new JdbcStorage(shared);
        JdbcStorage nodeB = new JdbcStorage(shared);

        URI uri = nodeA.put("dev/myflow/out.txt",
            new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));

        assertThat(nodeB.exists(uri)).isTrue();
        assertThat(new String(nodeB.get(uri).readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload");
        assertThat(nodeB.size(uri)).isEqualTo("payload".length());
    }

    @Test
    void concurrencyLimitIsClusterWide() {
        JdbcDatabase shared = JdbcDatabase.h2InMemory("hard_concurrency");
        JdbcLock lock = new JdbcLock(shared);
        // Two executor nodes share the same concurrency table.
        ConcurrencyStore nodeA = new JdbcConcurrencyStore(shared, lock, "node-A");
        ConcurrencyStore nodeB = new JdbcConcurrencyStore(shared, lock, "node-B");

        String flowKey = "|dev|singleton";
        // limit: 1 — node A admits the first execution.
        assertThat(nodeA.tryAdmit(flowKey, "exec-1", 1)).isTrue();
        // Node B must NOT admit a second execution of the same flow: the slot is taken cluster-wide.
        assertThat(nodeB.tryAdmit(flowKey, "exec-2", 1)).isFalse();

        nodeB.enqueue(flowKey, "exec-2");
        // When node A finishes exec-1, the queued exec-2 is promoted (any node can release).
        assertThat(nodeA.releaseAndPromote(flowKey, "exec-1")).isEqualTo("exec-2");
        // exec-2 now holds the only slot; a third still cannot get in.
        assertThat(nodeA.tryAdmit(flowKey, "exec-3", 1)).isFalse();
    }

    @Test
    void subflowJoinsViaPersistedParentRef_notAnInJvmMap() throws Exception {
        JacksonMapper mappers = new JacksonMapper(pluginRegistry());
        JdbcDatabase shared = JdbcDatabase.h2InMemory("hard_subflow");

        // The in-JVM subflowParents map is gone: the parent ref lives on the persisted child execution,
        // so the join works through the repository — the path a different node would take.
        try (DistributedEngine engine = new DistributedEngine(shared, mappers)) {
            Flow child = new YamlFlowParser(mappers).parse("""
                id: child_ok
                namespace: dev
                tasks:
                  - id: c1
                    type: io.tranto.plugin.core.log.Log
                    message: "child ran"
                """);
            Flow parent = new YamlFlowParser(mappers).parse("""
                id: parent_ok
                namespace: dev
                tasks:
                  - id: call
                    type: io.tranto.plugin.core.flow.Subflow
                    namespace: dev
                    flowId: child_ok
                  - id: after
                    type: io.tranto.plugin.core.log.Log
                    message: "parent continues"
                """);
            engine.register(child);
            Execution execution = engine.run(parent, Map.of(), Duration.ofSeconds(20));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("call").get().getState().current())
                .isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("after")).isPresent();
        }
    }

    private PluginRegistry pluginRegistry() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return registry;
    }
}
