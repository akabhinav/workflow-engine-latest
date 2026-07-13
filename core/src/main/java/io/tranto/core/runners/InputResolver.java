package io.tranto.core.runners;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.models.flows.Input;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a flow's declared {@link Input}s against the values supplied when an execution starts:
 * applies defaults, coerces each value to its declared {@link Input.Type}, and enforces
 * {@code required}. A violation raises {@link InputValidationException}, which the engine turns into
 * a FAILED execution before any task runs.
 *
 * <p>Undeclared supplied values are passed through untouched (lenient), so callers can attach ad-hoc
 * context without every key having to be declared.</p>
 */
public class InputResolver {

    /** Raised when a required input is missing or a value cannot be coerced to its declared type. */
    public static class InputValidationException extends RuntimeException {
        public InputValidationException(final String message) {
            super(message);
        }
    }

    private final ObjectMapper json;

    public InputResolver() {
        this(new ObjectMapper());
    }

    public InputResolver(final ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param declared the flow's declared inputs (may be null/empty)
     * @param provided the values supplied for this execution (may be null/empty)
     * @return the resolved, coerced input map
     * @throws InputValidationException if a required input is missing or a value is invalid
     */
    public Map<String, Object> resolve(final List<Input> declared, final Map<String, Object> provided) {
        Map<String, Object> supplied = provided == null ? Map.of() : provided;
        Map<String, Object> resolved = new LinkedHashMap<>();

        if (declared != null) {
            for (Input input : declared) {
                String key = input.key();
                if (key == null || key.isBlank()) {
                    throw new InputValidationException("An input is missing its id/name");
                }
                Object raw = supplied.containsKey(key) ? supplied.get(key) : input.getDefaults();
                if (raw == null) {
                    if (input.isRequired()) {
                        throw new InputValidationException("Missing required input '" + key + "'");
                    }
                    continue; // optional and absent — leave it out
                }
                resolved.put(key, coerce(key, input.getType(), raw));
            }
        }

        // Pass through any supplied values that weren't declared (lenient by design).
        for (Map.Entry<String, Object> e : supplied.entrySet()) {
            resolved.putIfAbsent(e.getKey(), e.getValue());
        }
        return resolved;
    }

    private Object coerce(final String key, final Input.Type type, final Object raw) {
        try {
            return switch (type == null ? Input.Type.STRING : type) {
                case STRING -> String.valueOf(raw);
                case INT -> raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
                case FLOAT -> raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString().trim());
                case BOOLEAN -> coerceBoolean(key, raw);
                case DURATION -> raw instanceof Duration d ? d : Duration.parse(raw.toString().trim());
                case JSON -> raw instanceof String s ? json.readValue(s, Object.class) : raw;
            };
        } catch (InputValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new InputValidationException(
                "Input '" + key + "' is not a valid " + type + ": '" + raw + "'");
        }
    }

    private boolean coerceBoolean(final String key, final Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = raw.toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("true")) {
            return true;
        }
        if (s.equals("false")) {
            return false;
        }
        throw new InputValidationException("Input '" + key + "' is not a valid BOOLEAN: '" + raw + "'");
    }
}
