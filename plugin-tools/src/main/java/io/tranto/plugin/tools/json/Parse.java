package io.tranto.plugin.tools.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.util.Collection;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Parses a JSON string into a structured value ({@code Map}, {@code List} or scalar) so downstream
 * tasks can navigate it via expressions, e.g. {@code {{ outputs.parse.value.items[0].name }}}.
 *
 * <p>Turns an opaque payload — an HTTP response body, a file's contents, a KV value — into data.
 * The parse is strict: malformed JSON fails the task with the parser's message.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Parse a JSON string into structured data",
    examples = @Example(
        title = "Parse an HTTP response body and read a field downstream",
        code = {
            "id: parse",
            "type: io.tranto.plugin.tools.json.Parse",
            "from: \"{{ outputs.request.body }}\""
        }
    )
)
public class Parse extends Task implements RunnableTask<Parse.ParseOutput> {

    /** A single shared, thread-safe mapper (immutable after construction). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The JSON text to parse. Pebble-rendered before parsing. */
    @PluginProperty(dynamic = true)
    private String from;

    @Override
    public ParseOutput run(final RunContext runContext) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("Parse requires a non-null 'from' value");
        }
        final String rendered = runContext.render(from);

        final Object value;
        try {
            value = MAPPER.readValue(rendered, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }

        final Integer size = switch (value) {
            case Map<?, ?> map -> map.size();
            case Collection<?> col -> col.size();
            case null, default -> null;
        };

        runContext.logger().debug("Parsed JSON into {}",
            value == null ? "null" : value.getClass().getSimpleName());
        return new ParseOutput(value, size);
    }

    /**
     * @param value the parsed value ({@code Map}/{@code List}/{@code String}/{@code Number}/
     *              {@code Boolean}/{@code null})
     * @param size  the element count for an object or array; {@code null} for scalars
     */
    public record ParseOutput(Object value, Integer size) implements Output {
    }
}
