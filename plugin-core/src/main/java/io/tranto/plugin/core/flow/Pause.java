package io.tranto.plugin.core.flow;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.tasks.Pausable;
import io.tranto.core.models.tasks.Task;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Pauses the execution when reached. The flow stays PAUSED until it is resumed, then continues with
 * the following tasks. Useful for manual approval gates.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Pause the execution until resumed")
public class Pause extends Task implements Pausable {
}
