package io.tranto.core.models.flows;

/**
 * A declared flow output: a named Pebble expression rendered once the execution reaches a terminal
 * state. Outputs can reference {@code {{ inputs.* }}} and task outputs via {@code {{ outputs.<taskId>.* }}}.
 * The rendered values are attached to the execution as its {@code outputs}.
 *
 * @param id    the output name (referenced by parents / the API)
 * @param value the Pebble expression to render at the end of the execution
 */
public record FlowOutput(String id, String value) {
}
