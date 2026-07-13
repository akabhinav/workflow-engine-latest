package io.tranto.core.runners;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tranto's Pebble function library layered on top of Pebble's built-ins. These are the runtime
 * helpers flows lean on: {@code now()} for timestamps, {@code uuid()} for correlation ids, and
 * {@code json()/fromJson()} to move between strings and structured values.
 *
 * <p>Kept small and additive; secret/kv/read functions are wired in as the corresponding engine
 * services land, following the same pattern.</p>
 */
public class TrantoPebbleExtension extends AbstractExtension {

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of(
            "now", noArg(args -> Instant.now().toString()),
            "uuid", noArg(args -> UUID.randomUUID().toString()),
            "json", oneArg("value", args -> writeJson(args.get("value"))),
            "fromJson", oneArg("value", args -> readJson(args.get("value")))
        );
    }

    private String writeJson(final Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("json(): cannot serialize value", e);
        }
    }

    private Object readJson(final Object value) {
        try {
            return json.readValue(String.valueOf(value), Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("fromJson(): invalid JSON '" + value + "'", e);
        }
    }

    /** A Pebble function taking no arguments. */
    private static Function noArg(final java.util.function.Function<Map<String, Object>, Object> body) {
        return new Function() {
            @Override
            public List<String> getArgumentNames() {
                return List.of();
            }

            @Override
            public Object execute(final Map<String, Object> args, final PebbleTemplate self,
                                  final EvaluationContext context, final int lineNumber) {
                return body.apply(args);
            }
        };
    }

    /** A Pebble function taking a single positional argument. */
    private static Function oneArg(final String name,
                                   final java.util.function.Function<Map<String, Object>, Object> body) {
        return new Function() {
            @Override
            public List<String> getArgumentNames() {
                return List.of(name);
            }

            @Override
            public Object execute(final Map<String, Object> args, final PebbleTemplate self,
                                  final EvaluationContext context, final int lineNumber) {
                return body.apply(args);
            }
        };
    }
}
