package io.tranto.webserver.api.dto;

import io.tranto.core.models.executions.Execution;

import java.util.List;

/**
 * API view of an execution. A DTO (not the domain object) so the wire contract can evolve
 * independently of the model.
 */
public record ExecutionResponse(
    String id,
    String namespace,
    String flowId,
    String state,
    List<TaskRunResponse> taskRuns
) {

    public record TaskRunResponse(String taskId, String state) {
    }

    /** Build the response from a domain execution. */
    public static ExecutionResponse of(final Execution execution) {
        List<TaskRunResponse> runs = execution.getTaskRunList() == null ? List.of()
            : execution.getTaskRunList().stream()
                .map(tr -> new TaskRunResponse(tr.getTaskId(), tr.getState().current().name()))
                .toList();
        return new ExecutionResponse(
            execution.getId(),
            execution.getNamespace(),
            execution.getFlowId(),
            execution.getState().current().name(),
            runs
        );
    }
}
