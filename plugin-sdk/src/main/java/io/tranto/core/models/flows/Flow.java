package io.tranto.core.models.flows;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.tranto.core.models.Label;
import io.tranto.core.models.tasks.FlowableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.models.triggers.AbstractTrigger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Optional;

/**
 * A workflow definition: the declarative unit users write in YAML. Identified by
 * {@code tenant + namespace + id + revision}. Owns the ordered list of tasks to run.
 *
 * <p>Phase 1 holds the identity + task body. Inputs, outputs, triggers, error/finally hooks,
 * concurrency and SLA are added in later phases (kept off now so the model stays small and the
 * round-trip is provable end-to-end first).</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public class Flow {

    /** Flow id, unique within its namespace. */
    @NotBlank
    private String id;

    /** Dotted namespace the flow belongs to (e.g. {@code company.team}). */
    @NotBlank
    private String namespace;

    /** Monotonic revision, bumped on every change; null until first persisted. */
    private Integer revision;

    /** Optional human description (markdown). */
    private String description;

    /** When true the flow cannot be executed. */
    private boolean disabled;

    /** Optional labels for organisation/filtering. */
    @Valid
    private List<Label> labels;

    /** Declared, typed inputs the flow accepts (resolved + validated when an execution starts). */
    @Valid
    private List<Input> inputs;

    /** Declared flow outputs: named Pebble expressions rendered when the execution terminates. */
    @Valid
    private List<FlowOutput> outputs;

    /** Optional per-flow concurrency control (limit + over-limit behaviour). */
    private Concurrency concurrency;

    /** Triggers that start executions of this flow (schedules, polls, ...). Polymorphic by {@code type}. */
    @Valid
    private List<AbstractTrigger> triggers;

    /** The tasks to run, in order. Polymorphic — resolved by the plugin registry. */
    @Valid
    @NotEmpty
    private List<Task> tasks;

    /** Tasks run only when a main task fails (error handler). */
    @Valid
    private List<Task> errors;

    /** Tasks run after main (+errors), always, regardless of outcome. */
    @Valid
    @JsonProperty("finally")
    private List<Task> finallyTasks;

    /** @return the tenant this flow belongs to, or null in single-tenant mode. */
    private String tenantId;

    /** @return the {@code finally} tasks (aliased to avoid the reserved word). */
    public List<Task> getFinally() {
        return finallyTasks;
    }

    /**
     * Find a task by id anywhere in the flow, descending into flowable tasks' children.
     *
     * @param taskId the task id to locate
     * @return the task, if present
     */
    public Optional<Task> findTask(final String taskId) {
        return tasks == null ? Optional.empty() : findTask(taskId, tasks);
    }

    private static Optional<Task> findTask(final String taskId, final List<Task> candidates) {
        for (Task task : candidates) {
            if (taskId.equals(task.getId())) {
                return Optional.of(task);
            }
            if (task instanceof FlowableTask<?> flowable) {
                Optional<Task> nested = findTask(taskId, flowable.allChildren());
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }
}

