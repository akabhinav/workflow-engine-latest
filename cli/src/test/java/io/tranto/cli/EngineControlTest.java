package io.tranto.cli;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Lifecycle control: pause/resume and kill. */
class EngineControlTest {

    private Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    private Execution await(final StandaloneEngine engine, final String id,
                            final Predicate<Execution> cond, final long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Execution ex = engine.executions().findById(id).orElse(null);
            if (ex != null && cond.test(ex)) {
                return ex;
            }
            Thread.sleep(20);
        }
        return engine.executions().findById(id).orElse(null);
    }

    @Test
    void shouldPauseThenResume() throws Exception {
        Flow flow = parse("""
            id: pausing
            namespace: dev
            tasks:
              - id: gate
                type: io.tranto.plugin.core.flow.Pause
              - id: after
                type: io.tranto.plugin.core.log.Log
                message: "resumed"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution submitted = engine.submit(flow, java.util.Map.of());

            Execution paused = await(engine, submitted.getId(),
                e -> e.getState().current() == StateType.PAUSED, 5000);
            assertThat(paused.getState().current()).isEqualTo(StateType.PAUSED);
            assertThat(paused.findTaskRunByTaskId("after")).isEmpty();

            engine.resume(submitted.getId());

            Execution done = await(engine, submitted.getId(),
                e -> e.getState().isTerminated(), 5000);
            assertThat(done.getState().current()).isEqualTo(StateType.SUCCESS);
            assertThat(done.findTaskRunByTaskId("after")).isPresent();
        }
    }

    @Test
    void shouldKillARunningExecution() throws Exception {
        Flow flow = parse("""
            id: killing
            namespace: dev
            tasks:
              - id: slow
                type: io.tranto.plugin.core.flow.Sleep
                duration: "PT10S"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution submitted = engine.submit(flow, java.util.Map.of());

            // Wait until the slow task is in flight, then kill.
            await(engine, submitted.getId(),
                e -> e.findTaskRunByTaskId("slow").isPresent(), 3000);
            engine.kill(submitted.getId());

            Execution killed = await(engine, submitted.getId(),
                e -> e.getState().isTerminated(), 3000);
            assertThat(killed.getState().current()).isEqualTo(StateType.KILLED);
        }
    }
}
