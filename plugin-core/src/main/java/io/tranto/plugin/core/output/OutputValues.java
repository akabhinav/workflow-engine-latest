package io.tranto.plugin.core.output;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Exposes a map of arbitrary {@code values} as task output.
 *
 * <p>String values are rendered as dynamic expressions before being emitted;
 * non-string values are passed through unchanged. This makes the task a convenient
 * way to compute and surface values for consumption by downstream tasks.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Output arbitrary values")
public class OutputValues extends Task implements RunnableTask<OutputValues.Values> {

    /**
     * The values to output. String entries are rendered as dynamic expressions;
     * other entries are emitted as-is.
     */
    @PluginProperty(dynamic = true)
    private Map<String, Object> values;

    @Override
    public Values run(final RunContext runContext) throws Exception {
        final Map<String, Object> rendered = new LinkedHashMap<>();

        if (values != null) {
            for (final Map.Entry<String, Object> entry : values.entrySet()) {
                final Object value = entry.getValue();
                if (value instanceof String s) {
                    rendered.put(entry.getKey(), runContext.render(s));
                } else {
                    rendered.put(entry.getKey(), value);
                }
            }
        }

        return new Values(rendered);
    }

    /**
     * Output holding the (possibly rendered) values.
     *
     * @param values the resolved values
     */
    public record Values(Map<String, Object> values) implements Output {
    }
}
