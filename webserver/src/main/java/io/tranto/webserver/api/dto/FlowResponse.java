package io.tranto.webserver.api.dto;

import io.tranto.core.models.flows.Flow;

/**
 * API view of a flow definition (summary).
 */
public record FlowResponse(String namespace, String id, Integer revision, int taskCount) {

    public static FlowResponse of(final Flow flow) {
        return new FlowResponse(
            flow.getNamespace(),
            flow.getId(),
            flow.getRevision(),
            flow.getTasks() == null ? 0 : flow.getTasks().size()
        );
    }
}
