package io.tranto.core.models.tasks;

/**
 * Marker for the typed value a task produces when it finishes. Implementations are normally
 * immutable Java {@code record}s (e.g. {@code record Output(URI uri, int count) implements
 * io.tranto.core.models.tasks.Output {}}) so a task's outputs are self-describing and can be
 * referenced downstream via {@code {{ outputs.<taskId>.<field> }}}.
 */
public interface Output {
}
