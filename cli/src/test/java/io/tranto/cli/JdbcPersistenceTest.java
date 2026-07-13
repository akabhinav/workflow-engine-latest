package io.tranto.cli;

import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.queues.JdbcQueue;
import io.tranto.core.repositories.jdbc.JdbcExecutionRepository;
import io.tranto.core.repositories.jdbc.JdbcFlowRepository;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The JDBC (H2) persistence layer round-trips flows/executions and delivers queued messages. */
class JdbcPersistenceTest {

    private JacksonMapper mappers() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new JacksonMapper(registry);
    }

    @Test
    void flowRepositoryRoundTripsThroughH2() throws Exception {
        JacksonMapper mappers = mappers();
        JdbcDatabase db = JdbcDatabase.h2InMemory("flowrepo");
        JdbcFlowRepository repo = new JdbcFlowRepository(db, mappers);

        Flow flow = new YamlFlowParser(mappers).parse("""
            id: persisted
            namespace: dev
            tasks:
              - id: hello
                type: io.tranto.plugin.core.log.Log
                message: "hi"
            """);
        repo.save(flow);

        Flow loaded = repo.findById(null, "dev", "persisted", null).orElseThrow();
        assertThat(loaded.getId()).isEqualTo("persisted");
        assertThat(loaded.getTasks()).hasSize(1);
        assertThat(loaded.getTasks().get(0).getType()).isEqualTo("io.tranto.plugin.core.log.Log");
    }

    @Test
    void executionRepositoryRoundTripsThroughH2() {
        JdbcDatabase db = JdbcDatabase.h2InMemory("execrepo");
        JdbcExecutionRepository repo = new JdbcExecutionRepository(db, mappers());

        Execution execution = Execution.newExecution("dev", "persisted", 1, Map.of(), null);
        repo.save(execution);

        Execution loaded = repo.findById(execution.getId()).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(execution.getId());
        assertThat(loaded.getState().current()).isEqualTo(StateType.CREATED);
    }

    @Test
    void jdbcQueueDeliversEmittedMessages() throws Exception {
        JacksonMapper mappers = mappers();
        JdbcDatabase db = JdbcDatabase.h2InMemory("queue");
        try (JdbcQueue<Execution> queue = new JdbcQueue<>(db, "executions", Execution.class, mappers.json())) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> received = new AtomicReference<>();
            queue.receive(e -> {
                received.set(e.getId());
                latch.countDown();
            });

            Execution execution = Execution.newExecution("dev", "persisted", 1, Map.of(), null);
            queue.emit(execution);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isEqualTo(execution.getId());
        }
    }
}
