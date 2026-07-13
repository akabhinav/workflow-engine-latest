package io.tranto.core.models.tasks;

import io.tranto.core.models.Plugin;

/**
 * Marks a task that pauses the execution when reached. The executor sets the execution to PAUSED
 * and the task's run to PAUSED; the execution stays there until it is resumed
 * ({@code engine.resume(id)} / an unpause API), at which point the flow continues past the task.
 */
public interface Pausable extends Plugin {
}
