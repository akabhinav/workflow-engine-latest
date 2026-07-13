package io.tranto.core.models.executions;

import io.tranto.core.models.Label;
import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.flows.State;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One run of a flow. Immutable: applying a task result produces a new {@code Execution} via
 * {@link #withTaskRun(TaskRun)} / {@link #withState(StateType)}. This immutability + per-execution
 * locking is what makes at-least-once delivery safe (a duplicate result re-applies the same
 * delta without corrupting state).
 */
@Value
@Builder(toBuilder = true)
@With
public class Execution {

    /** Unique execution id. */
    String id;

    /** Tenant, or null in single-tenant mode. */
    String tenantId;

    /** Namespace of the flow. */
    String namespace;

    /** Flow id. */
    String flowId;

    /** Flow revision this execution was created from. */
    Integer flowRevision;

    /** The per-task runs. */
    List<TaskRun> taskRunList;

    /** Resolved input values. */
    Map<String, Object> inputs;

    /** Rendered flow outputs, populated when the execution reaches a terminal state. */
    Map<String, Object> outputs;

    /** Labels carried from the flow/trigger. */
    List<Label> labels;

    /** If this is a subflow child, the parent execution waiting on it (null for a top-level execution). */
    String parentExecutionId;

    /** If this is a subflow child, the parent task-run to complete when this execution ends. */
    String parentTaskRunId;

    /** Overall execution state. */
    State state;

    /** Create a fresh CREATED execution for a flow. */
    public static Execution newExecution(final String namespace,
                                         final String flowId,
                                         final Integer revision,
                                         final Map<String, Object> inputs,
                                         final List<Label> labels) {
        return Execution.builder()
            .id(UUID.randomUUID().toString())
            .namespace(namespace)
            .flowId(flowId)
            .flowRevision(revision)
            .inputs(inputs == null ? Map.of() : Map.copyOf(inputs))
            .labels(labels == null ? List.of() : List.copyOf(labels))
            .taskRunList(List.of())
            .state(State.created())
            .build();
    }

    /** @return a copy whose overall state transitioned to {@code next}. */
    public Execution withState(final StateType next) {
        return this.toBuilder().state(this.state.withState(next)).build();
    }

    /**
     * @return a copy with {@code taskRun} appended (if new) or replaced (if it already exists by id).
     */
    public Execution withTaskRun(final TaskRun taskRun) {
        List<TaskRun> current = taskRunList == null ? List.of() : taskRunList;
        boolean exists = current.stream().anyMatch(r -> r.getId().equals(taskRun.getId()));
        List<TaskRun> updated;
        if (exists) {
            updated = TaskRun.replace(current, taskRun);
        } else {
            updated = new ArrayList<>(current);
            updated.add(taskRun);
        }
        return this.withTaskRunList(List.copyOf(updated));
    }

    /** @return the task-run for a given task id, if present. */
    public Optional<TaskRun> findTaskRunByTaskId(final String taskId) {
        return safeRuns().stream().filter(r -> r.getTaskId().equals(taskId)).findFirst();
    }

    /**
     * @return the task-run for a given task id nested under a specific parent flowable task-run
     *         ({@code parentTaskRunId} may be null for the top level).
     */
    public Optional<TaskRun> findTaskRun(final String taskId, final String parentTaskRunId) {
        return safeRuns().stream()
            .filter(r -> r.getTaskId().equals(taskId)
                && java.util.Objects.equals(r.getParentTaskRunId(), parentTaskRunId))
            .findFirst();
    }

    /** @return true if every task-run present is terminated. */
    public boolean allTaskRunsTerminated() {
        List<TaskRun> runs = safeRuns();
        return !runs.isEmpty() && runs.stream().allMatch(TaskRun::isTerminated);
    }

    /** @return true if any task-run has failed. */
    public boolean hasFailed() {
        return safeRuns().stream().anyMatch(r -> r.getState().current() == StateType.FAILED);
    }

    private List<TaskRun> safeRuns() {
        return taskRunList == null ? List.of() : taskRunList;
    }
}
