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

import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-flow concurrency: over-limit executions are queued (or cancelled) and admitted as slots free. */
class ConcurrencyTest {

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
    void queuesOverLimitThenAdmitsWhenSlotFrees() throws Exception {
        Flow flow = parse("""
            id: limited
            namespace: dev
            concurrency:
              limit: 1
              behavior: QUEUE
            tasks:
              - id: gate
                type: io.tranto.plugin.core.flow.Pause
              - id: done
                type: io.tranto.plugin.core.log.Log
                message: "done"
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution first = engine.submit(flow, Map.of());
            await(engine, first.getId(), e -> e.getState().current() == StateType.PAUSED, 5000);

            Execution second = engine.submit(flow, Map.of());
            Execution queued = await(engine, second.getId(),
                e -> e.getState().current() == StateType.QUEUED, 5000);
            assertThat(queued.getState().current()).isEqualTo(StateType.QUEUED);

            // Free the slot: the first finishes, the queued execution is admitted and starts running.
            engine.resume(first.getId());
            await(engine, first.getId(), e -> e.getState().isTerminated(), 5000);

            Execution admitted = await(engine, second.getId(),
                e -> e.getState().current() == StateType.PAUSED, 5000);
            assertThat(admitted.getState().current()).isEqualTo(StateType.PAUSED);

            engine.resume(second.getId());
            Execution done = await(engine, second.getId(), e -> e.getState().isTerminated(), 5000);
            assertThat(done.getState().current()).isEqualTo(StateType.SUCCESS);
        }
    }

    @Test
    void cancelsOverLimitWhenBehaviorIsCancel() throws Exception {
        Flow flow = parse("""
            id: limited_cancel
            namespace: dev
            concurrency:
              limit: 1
              behavior: CANCEL
            tasks:
              - id: gate
                type: io.tranto.plugin.core.flow.Pause
            """);

        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution first = engine.submit(flow, Map.of());
            await(engine, first.getId(), e -> e.getState().current() == StateType.PAUSED, 5000);

            Execution second = engine.submit(flow, Map.of());
            Execution cancelled = await(engine, second.getId(),
                e -> e.getState().isTerminated(), 5000);
            assertThat(cancelled.getState().current()).isEqualTo(StateType.CANCELLED);
        }
    }
}
