package io.tranto.plugin.core.kv;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.tasks.VoidOutput;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

/**
 * Stores a value in the flow-namespace KV store, optionally with a TTL. Durable state that survives
 * beyond this execution (counters, cursors, cross-flow handoffs).
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Set a value in the KV store")
public class Set extends Task implements RunnableTask<VoidOutput> {

    @PluginProperty(dynamic = true)
    private String key;

    @PluginProperty(dynamic = true)
    private String value;

    /** Optional time-to-live (ISO-8601 duration, e.g. {@code PT1H}); null = no expiry. */
    private Duration ttl;

    @Override
    public VoidOutput run(final RunContext runContext) throws Exception {
        runContext.kv().put(runContext.render(key), runContext.render(value), ttl);
        return new VoidOutput();
    }
}
