package io.tranto.plugin.core.kv;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Reads a value from the flow-namespace KV store and exposes it as {@code {{ outputs.<id>.value }}}.
 * Missing keys yield a null value (not an error) unless the caller asserts otherwise downstream.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Get a value from the KV store")
public class Get extends Task implements RunnableTask<Get.GetOutput> {

    @PluginProperty(dynamic = true)
    private String key;

    @Override
    public GetOutput run(final RunContext runContext) throws Exception {
        Object value = runContext.kv().get(runContext.render(key)).orElse(null);
        return new GetOutput(value);
    }

    /** @param value the stored value, or null if the key was absent. */
    public record GetOutput(Object value) implements Output {
    }
}
