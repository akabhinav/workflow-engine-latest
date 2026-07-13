package io.tranto.core.models.executions;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.flows.State;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.List;
import java.util.UUID;

/**
 * One run of one task within an execution. Immutable: state changes produce a new instance via
 * {@link #withState(StateType)} / Lombok {@code with*} accessors, so the executor can apply
 * results idempotently.
 */
@Value
@Builder(toBuilder = true)
@With
public class TaskRun {

    /** Unique id of this task-run. */
    String id;

    /** The execution this run belongs to. */
    String executionId;

    /** The id of the task in the flow this run corresponds to. */
    String taskId;

    /** For iterated/dynamic tasks, the per-iteration value; null otherwise. */
    String value;

    /** Parent task-run id for nested (flowable) tasks; null at the top level. */
    String parentTaskRunId;

    /** Current state of this task-run. */
    State state;

    /** Captured outputs of the task, once it has run. */
    Object outputs;

    /** Error message if the task-run failed. */
    String error;

    /** How many retry attempts have been consumed (0 on the first run). */
    @lombok.Builder.Default
    int attempts = 0;

    /** Create a fresh CREATED task-run for a task id within an execution. */
    public static TaskRun of(final String executionId, final String taskId) {
        return of(executionId, taskId, null);
    }

    /** Create a fresh CREATED task-run nested under a parent flowable task-run. */
    public static TaskRun of(final String executionId, final String taskId, final String parentTaskRunId) {
        return TaskRun.builder()
            .id(UUID.randomUUID().toString())
            .executionId(executionId)
            .taskId(taskId)
            .parentTaskRunId(parentTaskRunId)
            .state(State.created())
            .build();
    }

    /** @return a copy transitioned to {@code next}. */
    public TaskRun withState(final StateType next) {
        return this.toBuilder().state(this.state.withState(next)).build();
    }

    /** @return a copy in SUCCESS carrying the given outputs. */
    public TaskRun success(final Object taskOutputs) {
        return this.toBuilder()
            .state(this.state.withState(StateType.SUCCESS))
            .outputs(taskOutputs)
            .build();
    }

    /** @return a copy in FAILED carrying the error message. */
    public TaskRun failed(final String errorMessage) {
        return this.toBuilder()
            .state(this.state.withState(StateType.FAILED))
            .error(errorMessage)
            .build();
    }

    /** @return a copy in WARNING (a tolerated failure) carrying the error message. */
    public TaskRun warning(final String errorMessage) {
        return this.toBuilder()
            .state(this.state.withState(StateType.WARNING))
            .error(errorMessage)
            .build();
    }

    /** @return a copy in SKIPPED (disabled or runIf false). */
    public TaskRun skipped() {
        return this.toBuilder()
            .state(this.state.withState(StateType.SKIPPED))
            .build();
    }

    /** convenience: is this run in a terminal state. */
    public boolean isTerminated() {
        return state.isTerminated();
    }

    /** helper used by the executor to keep the immutable list handling readable. */
    public static List<TaskRun> replace(final List<TaskRun> runs, final TaskRun updated) {
        return runs.stream()
            .map(r -> r.getId().equals(updated.getId()) ? updated : r)
            .toList();
    }
}
