package io.tranto.core.runners;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.tranto.core.exceptions.IllegalVariableEvaluationException;

import java.io.StringWriter;
import java.util.Map;

/**
 * Renders Pebble expressions ({@code {{ ... }}} / {@code {% ... %}}) against a variable map.
 * Wraps a single reusable {@link PebbleEngine}. Strings with no {@code {} are returned as-is
 * (the common case) to avoid template compilation overhead on the hot path.
 *
 * <p>Phase 2 covers variable access and the built-in Pebble filter/function set. The full Tranto
 * function library (kv, secret, now, fromJson, ...) is layered on in later phases via engine
 * extensions.</p>
 */
public class VariableRenderer {

    private final PebbleEngine engine;

    public VariableRenderer() {
        this.engine = new PebbleEngine.Builder()
            .autoEscaping(false)
            .cacheActive(true)
            .strictVariables(false)
            .newLineTrimming(false)
            .extension(new TrantoPebbleExtension())
            .build();
    }

    /**
     * Render a template against {@code variables}.
     *
     * @param template  the expression/string
     * @param variables the context
     * @return the rendered result (or the input unchanged if it has no template markers)
     */
    public String render(final String template, final Map<String, Object> variables)
        throws IllegalVariableEvaluationException {

        if (template == null || template.indexOf('{') == -1) {
            return template;
        }
        try {
            PebbleTemplate compiled = engine.getLiteralTemplate(template);
            StringWriter writer = new StringWriter();
            compiled.evaluate(writer, variables == null ? Map.of() : variables);
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalVariableEvaluationException(
                "Failed to render expression: " + template, e);
        }
    }
}
