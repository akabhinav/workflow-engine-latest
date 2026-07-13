package io.tranto.cli;

import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.DistributedEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributed runtime: the same executor/worker, wired onto JDBC queues + repositories. Proves
 * (a) a flow runs end-to-end over the JDBC transport, and (b) a separate executor process and worker
 * process, sharing only a database, coordinate to run a flow — the shape of a real cluster.
 */
class DistributedEngineTest {

    private JacksonMapper mappers() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new JacksonMapper(registry);
    }

    private Flow parse(final JacksonMapper mappers, final String yaml) throws Exception {
        return new YamlFlowParser(mappers).parse(yaml);
    }

    @Test
    void runsAflowEndToEndOverJdbcQueues() throws Exception {
        JacksonMapper mappers = mappers();
        JdbcDatabase db = JdbcDatabase.h2InMemory("dist_all");

        try (DistributedEngine engine = new DistributedEngine(db, mappers)) {
            Execution execution = engine.run(parse(mappers, """
                id: dist_hello
                namespace: dev
                tasks:
                  - id: work
                    type: io.tranto.plugin.core.debug.Return
                    format: "done-on-jdbc"
                """), Map.of(), Duration.ofSeconds(20));

            assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(execution.findTaskRunByTaskId("work").get().getOutputs().toString())
                .contains("done-on-jdbc");
        }
    }

    @Test
    void splitExecutorAndWorkerCoordinateThroughSharedDatabase() throws Exception {
        JacksonMapper mappers = mappers();
        JdbcDatabase shared = JdbcDatabase.h2InMemory("dist_split");

        // One process runs only the executor + scheduler; another runs only the worker.
        try (DistributedEngine executorNode = new DistributedEngine(shared, mappers, true, false, true);
             DistributedEngine workerNode = new DistributedEngine(shared, mappers, false, true, false)) {

            Flow flow = parse(mappers, """
                id: dist_split
                namespace: dev
                tasks:
                  - id: work
                    type: io.tranto.plugin.core.debug.Return
                    format: "processed-by-worker-node"
                """);

            // Submit on the executor node; the worker node (separate instance) must pick up the task.
            Execution submitted = executorNode.submit(flow, Map.of());

            Execution done = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                Execution current = executorNode.executions().findById(submitted.getId()).orElse(null);
                if (current != null && current.getState().isTerminated()) {
                    done = current;
                    break;
                }
                Thread.sleep(30);
            }

            assertThat(done).as("execution should terminate via cross-node coordination").isNotNull();
            assertThat(done.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(done.findTaskRunByTaskId("work").get().getOutputs().toString())
                .contains("processed-by-worker-node");
        }
    }
}
